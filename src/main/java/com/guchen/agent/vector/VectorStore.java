package com.guchen.agent.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量库抽象 —— 让 Qdrant 与 Milvus 可以互换，从而做**真实对比实验**。
 *
 * 简历上要写的不是「我用了两个向量库」，而是：
 *   同一批 500 条茶知识 chunk 分别写入 Qdrant(HNSW) 与 Milvus(HNSW/IVF_FLAT)，
 *   在自建 100 条评测集上对比 Recall@5 / P99 延迟 / 内存占用，
 *   最终生产链路选 Qdrant（单机运维成本低、payload 过滤更灵活）。
 */
public interface VectorStore {

    /** 建集合（含向量维度与索引） */
    void ensureCollection(int dimension);

    /** 批量写入 */
    void upsert(List<VectorPoint> points);

    /** 向量检索，返回 topK 命中 */
    List<SearchHit> search(float[] queryVector, int topK, Map<String, Object> filter);

    /** 删除集合 */
    void dropCollection();

    /** 后端标识，用于日志与对比报告 */
    String backend();

    record VectorPoint(String id, float[] vector, String content, Map<String, Object> payload) {}

    record SearchHit(String id, float score, String content, Map<String, Object> payload) {}
}
