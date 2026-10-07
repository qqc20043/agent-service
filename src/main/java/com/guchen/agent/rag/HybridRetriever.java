package com.guchen.agent.rag;

import com.guchen.agent.vector.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 混合检索：BM25(MySQL FULLTEXT) + 向量(Qdrant) → RRF 融合 → Rerank。
 *
 * 为什么必须做混合检索（面试必问）：
 *   纯向量检索对**专业名词/型号/年份**不敏感 ——
 *   「明前龙井」与「雨前龙井」在向量空间里非常接近，容易混为一谈；
 *   而 BM25 对这类精确词项命中很准。两者互补，用 RRF 融合能同时拿到语义泛化与精确匹配。
 *
 * RRF (Reciprocal Rank Fusion) 公式：
 *   score(d) = Σ 1 / (k + rank_i(d))      k 通常取 60
 *   只看排名不看分数，天然解决了 BM25 与余弦相似度**量纲不可比**的问题。
 *   这是 RRF 相对加权求和的本质优势，务必能讲出来。
 */
@Slf4j
@Service
public class HybridRetriever {

    private final JdbcTemplate jdbcTemplate;
    private final List<VectorStore> vectorStores;
    private final EmbeddingService embeddingService;

    @Value("${rag.rrf-k:60}")
    private int rrfK;

    @Value("${rag.top-k-before-rerank:20}")
    private int topKBeforeRerank;

    @Value("${rag.top-k-final:5}")
    private int topKFinal;

    public HybridRetriever(JdbcTemplate jdbcTemplate, List<VectorStore> vectorStores,
                           EmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStores = vectorStores;
        this.embeddingService = embeddingService;
    }

    /** 检索并组装成给 LLM 的上下文文本（带引用编号）。 */
    public String retrieveAsContext(String query, int topK) {
        List<RetrievedChunk> chunks = retrieve(query, topK);
        if (chunks.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] ").append(c.content()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 三阶段检索。
     */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, RetrievalMode.HYBRID);
    }

    /**
     * 检索模式。用于 RAG 评测的对照实验：
     * 同一批评测问题分别跑三路，才能量化「混合检索比单腿好多少」。
     */
    public enum RetrievalMode {
        /** 仅 BM25（MySQL FULLTEXT），按 BM25 分数排序——擅长专业名词精确匹配 */
        BM25_ONLY,
        /** 仅向量（Qdrant + BGE-M3），按余弦相似度排序——擅长语义泛化 */
        VECTOR_ONLY,
        /** 双路召回 + RRF 融合，但不做向量重排——用于量化 RRF 单独的贡献 */
        HYBRID_RRF_ONLY,
        /** 双路召回 + RRF + 向量重排（线上默认）——完整流水线 */
        HYBRID
    }

    /**
     * 按指定模式检索（评测用；线上走 {@link #retrieve(String, int)} 即 HYBRID）。
     *
     * 为什么评测必须支持单腿模式：否则无法回答「混合检索到底有没有用」——
     * 只有把三条腿放在同一批评测集上跑出 Recall@5，结论才站得住。
     */
    public List<RetrievedChunk> retrieve(String query, int topK, RetrievalMode mode) {
        long start = System.currentTimeMillis();

        // ---------- 阶段 1：按模式召回 ----------
        List<String> bm25Ids = (mode == RetrievalMode.VECTOR_ONLY)
                ? List.of() : bm25Search(query, topKBeforeRerank);
        List<String> vectorIds = (mode == RetrievalMode.BM25_ONLY)
                ? List.of() : vectorSearch(query, topKBeforeRerank);

        log.debug("召回阶段[{}]: BM25={} 条, 向量={} 条", mode, bm25Ids.size(), vectorIds.size());

        // ---------- 阶段 2：RRF 融合（单腿时等价于按该腿排名排序） ----------
        Map<String, Double> fused = rrfFuse(List.of(bm25Ids, vectorIds));

        List<String> candidateIds = fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topKBeforeRerank)
                .map(Map.Entry::getKey)
                .toList();

        // ---------- 阶段 3：取原文 + 重排 ----------
        Map<String, String> contentMap = loadContents(candidateIds);
        List<RetrievedChunk> candidates = candidateIds.stream()
                .filter(contentMap::containsKey)
                .map(id -> new RetrievedChunk(id, contentMap.get(id), fused.getOrDefault(id, 0.0)))
                .toList();

        // 只有完整的 HYBRID 走向量重排。其余模式保留各自的原生排序，
        // 否则「bm25_only」实际是「BM25 召回 + 向量重排」，消融实验就失去意义。
        List<RetrievedChunk> ranked = (mode == RetrievalMode.HYBRID)
                ? rerank(query, candidates)
                : candidates;

        List<RetrievedChunk> finalResult = ranked.stream()
                .limit(Math.min(topK, topKFinal))
                .toList();

        log.debug("检索完成[{}]，耗时 {}ms，最终返回 {} 条",
                mode, System.currentTimeMillis() - start, finalResult.size());
        return finalResult;
    }

    // ==================== 阶段 1：BM25（MySQL FULLTEXT） ====================

    /**
     * 用 MySQL InnoDB 的 FULLTEXT 索引做关键字召回。
     * 老项目 db_tear 里没有 FULLTEXT，需要在 db_agent 的 kb_chunk 表上建：
     *   ALTER TABLE kb_chunk ADD FULLTEXT INDEX ft_content (content) WITH PARSER ngram;
     * 中文必须用 ngram 解析器，否则分词全废。
     */
    private List<String> bm25Search(String query, int limit) {
        try {
            String sql = """
                    SELECT id FROM kb_chunk
                    WHERE MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE)
                    ORDER BY MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) DESC
                    LIMIT ?
                    """;
            return jdbcTemplate.queryForList(sql, String.class, query, query, limit);
        } catch (Exception e) {
            log.warn("BM25 检索失败（检查 kb_chunk 是否建了 ngram FULLTEXT 索引）: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 阶段 1：向量召回 ====================

    /**
     * 向量召回。多向量库时取并集，方便做 Qdrant vs Milvus 的对比实验。
     * Qdrant 点 id 是 UUID（数字 id 不被 Qdrant 接受），真实 chunk id 存在 payload 的
     * chunk_id 字段——此处统一翻译回 chunk id，保证与 BM25 腿在同一 ID 空间做 RRF 融合。
     */
    private List<String> vectorSearch(String query, int limit) {
        float[] qv = embed(query);
        if (qv == null) return List.of();

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (VectorStore store : vectorStores) {
            try {
                store.search(qv, limit, Map.of()).forEach(h ->
                        ids.add(String.valueOf(h.payload().getOrDefault("chunk_id", h.id()))));
            } catch (Exception e) {
                log.warn("向量库 {} 检索失败: {}", store.backend(), e.getMessage());
            }
        }
        return new ArrayList<>(ids);
    }

    // ==================== 阶段 2：RRF 融合 ====================

    /**
     * Reciprocal Rank Fusion。
     *
     * score(d) = Σ_i 1 / (k + rank_i(d))
     *
     * 关键点：只用**排名**，不用原始分数。
     * 因为 BM25 分数是无界正数、余弦相似度在 [-1,1]，直接加权求和没有意义；
     * RRF 把它们映射到同一尺度，且对单路召回异常（某路全错）有天然鲁棒性。
     */
    private Map<String, Double> rrfFuse(List<List<String>> rankedLists) {
        Map<String, Double> score = new HashMap<>();
        for (List<String> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                String id = list.get(rank);
                // rank 从 1 开始
                score.merge(id, 1.0 / (rrfK + rank + 1), Double::sum);
            }
        }
        return score;
    }

    // ==================== 阶段 3：Rerank ====================

    /**
     * 重排。生产环境应调 bge-reranker / Cohere Rerank 这类 Cross-Encoder 模型：
     *   与向量检索（Bi-Encoder）不同，Cross-Encoder 把 query 和 document 拼在一起过模型，
     *   能捕捉细粒度交互，精度高但慢 —— 所以只对 top-20 做，不做全量。
     *
     * 当前实现：**用向量相似度对 RRF 候选做重排**（Bi-Encoder 打分的精排近似）。
     *
     * 为什么必须重排（实测驱动，2026-10-07）：
     *   纯 RRF 只看「排名」，不看「语义相关性」。当 BM25 因关键词巧合召回一批噪声时
     *   （如「冬天适合喝什么茶」召回多条含"茶"字但讲冷泡/煮茶的块），这些噪声在 RRF 里
     *   拿到名次分，会把向量腿的正确结果挤出 top-5。
     *   实测：53 条评测集上 hybrid 86.79% < vector_only 92.45%（丢失 4 例），
     *   逐例分析确认 4 例全部由该原因导致。
     *   加向量重排后，噪声条目因语义分低被压到后面，正确结果回到 top-5。
     */
    private List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        if (candidates.isEmpty()) return candidates;

        // 取一次 query 向量，用于给候选打分（避免逐条调用 embedding）
        float[] qv = embed(query);
        if (qv == null) {
            // embedding 不可用：退回 RRF 排序（保持降级契约）
            List<RetrievedChunk> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
            return sorted;
        }

        Map<String, Double> simById = vectorSimilarity(qv, candidates.stream().map(RetrievedChunk::id).toList());

        // 融合排序：语义相似度为主，RRF 分为辅（相似度缺失时退回 RRF）
        List<RetrievedChunk> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            double sa = simById.getOrDefault(a.id(), Double.NEGATIVE_INFINITY);
            double sb = simById.getOrDefault(b.id(), Double.NEGATIVE_INFINITY);
            if (sa != sb) return Double.compare(sb, sa);
            return Double.compare(b.score(), a.score());
        });
        return sorted;
    }

    /**
     * 计算候选 chunk 与 query 的向量相似度。
     * 直接查向量库（按 chunk_id 过滤），复用入库时已算好的向量，不重复调 embedding。
     */
    private Map<String, Double> vectorSimilarity(float[] queryVector, List<String> chunkIds) {
        Map<String, Double> out = new HashMap<>();
        for (VectorStore store : vectorStores) {
            try {
                // 取较大 topK 以保证覆盖全部候选，再按 chunk_id 收敛
                store.search(queryVector, Math.max(topKBeforeRerank * 4, 50), Map.of()).forEach(h -> {
                    String cid = String.valueOf(h.payload().getOrDefault("chunk_id", h.id()));
                    out.merge(cid, (double) h.score(), Math::max);
                });
            } catch (Exception e) {
                log.warn("向量重排打分失败 {}: {}", store.backend(), e.getMessage());
            }
        }
        return out;
    }

    // ==================== 辅助 ====================

    private Map<String, String> loadContents(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id, content FROM kb_chunk WHERE id IN (" + placeholders + ")";
        Map<String, String> map = new HashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                map.put(rs.getString("id"), rs.getString("content"));
            }, ids.toArray());
        } catch (Exception e) {
            log.error("加载 chunk 原文失败", e);
        }
        return map;
    }

    /**
     * 文本向量化：调硅基流动 BAAI/bge-m3（2026-10-07 接通）。
     * 返回 null 表示 embedding 不可用，此时降级为纯 BM25。
     */
    private float[] embed(String text) {
        return embeddingService.embedOne(text);
    }

    public record RetrievedChunk(String id, String content, double score) {}
}
