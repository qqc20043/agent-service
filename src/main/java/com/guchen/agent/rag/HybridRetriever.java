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
        long start = System.currentTimeMillis();

        // ---------- 阶段 1：双路召回 ----------
        List<String> bm25Ids = bm25Search(query, topKBeforeRerank);
        List<String> vectorIds = vectorSearch(query, topKBeforeRerank);

        log.debug("召回阶段: BM25={} 条, 向量={} 条", bm25Ids.size(), vectorIds.size());

        // ---------- 阶段 2：RRF 融合 ----------
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

        List<RetrievedChunk> finalResult = rerank(query, candidates).stream()
                .limit(Math.min(topK, topKFinal))
                .toList();

        log.debug("混合检索完成，耗时 {}ms，最终返回 {} 条",
                System.currentTimeMillis() - start, finalResult.size());
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
     * 此处为占位实现，保证链路可跑；接入真实 reranker 只需替换方法体。
     */
    private List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        // TODO 接入真实 Rerank 服务：
        //   POST {rerank-endpoint} { "query": query, "documents": [...], "top_n": 5 }
        // 当前按 RRF 分数降序作为兜底
        List<RetrievedChunk> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return sorted;
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
