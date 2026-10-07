package com.guchen.agent.rag;

import com.guchen.agent.vector.VectorStore;
import com.guchen.agent.vector.VectorStore.VectorPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识入库：把 db_agent.kb_chunk 的知识块批量向量化后写入向量库。
 *
 * 流程（对应 RAG 标准链路的「切分 → Embedding → 入库」段）：
 *   MySQL kb_chunk → 分批（16 条/批）调 BAAI/bge-m3 → 向量 upsert 到 Qdrant（collection 维度 1024）。
 * BM25 那条腿不需要入库——kb_chunk 上已建 ngram FULLTEXT 索引，直接查 MySQL。
 *
 * 触发方式：POST /api/agent/knowledge/ingest（知识内容变更后手动触发；本机演示不设防）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private static final int DIMENSION = 1024;   // BAAI/bge-m3 输出维度
    private static final int BATCH_SIZE = 16;

    private final JdbcTemplate jdbcTemplate;
    private final List<VectorStore> vectorStores;
    private final EmbeddingService embeddingService;

    @PostMapping("/ingest")
    public Map<String, Object> ingestAll() {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, content FROM kb_chunk ORDER BY id");
        if (rows.isEmpty()) {
            return Map.of("ingested", 0, "note", "kb_chunk 为空");
        }

        // 建集合（幂等：Qdrant 已存在同名集合时内部会忽略）
        for (VectorStore store : vectorStores) {
            store.ensureCollection(DIMENSION);
        }

        int ingested = 0, failed = 0;
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<Map<String, Object>> batch = rows.subList(i, Math.min(rows.size(), i + BATCH_SIZE));
            List<String> texts = batch.stream().map(r -> String.valueOf(r.get("content"))).toList();
            List<float[]> vectors = embeddingService.embed(texts);
            if (vectors.size() != texts.size()) {
                failed += texts.size();
                continue;
            }
            for (int j = 0; j < batch.size(); j++) {
                String id = String.valueOf(batch.get(j).get("id"));
                // Qdrant 点 id 必须是 UUID（数字 id 会被拒）：用 chunk id 派生确定性 UUID，
                // 重复入库覆盖同一点（幂等）；真实 chunk id 放 payload，检索侧回读以对齐 BM25 腿
                String pointId = java.util.UUID.nameUUIDFromBytes(
                        ("kb-chunk-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                VectorPoint point = new VectorPoint(pointId, vectors.get(j), texts.get(j),
                        Map.of("source", "kb_chunk", "chunk_id", id));
                for (VectorStore store : vectorStores) {
                    try {
                        store.upsert(List.of(point));
                    } catch (Exception e) {
                        log.warn("upsert 到 {} 失败 id={}: {}", store.backend(), id, e.getMessage());
                        failed++;
                    }
                }
                ingested++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("ingested", ingested);
        out.put("failed", failed);
        out.put("dimension", DIMENSION);
        out.put("backends", vectorStores.stream().map(VectorStore::backend).toList());
        out.put("costMs", System.currentTimeMillis() - t0);
        log.info("知识入库完成: {}", out);
        return out;
    }
}
