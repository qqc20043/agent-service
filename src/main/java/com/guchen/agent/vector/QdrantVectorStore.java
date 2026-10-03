package com.guchen.agent.vector;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 实现 —— 生产链路主向量库。
 *
 * 选型理由（面试要能说出来）：
 *  - 单进程单二进制，无 etcd/minio 依赖，部署与运维成本远低于 Milvus；
 *  - payload 过滤是原生的，做「按茶类/主题隔离检索」很自然；
 *  - 支持 HNSW 增量索引，写入即可查，无需手动 build index；
 *  - 缺点：超大规模（亿级）分片能力不如 Milvus —— 这也是保留 Milvus 做对比的原因。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vector.qdrant.enabled", havingValue = "true", matchIfMissing = true)
public class QdrantVectorStore implements VectorStore {

    @Value("${vector.qdrant.host:127.0.0.1}")
    private String host;

    @Value("${vector.qdrant.port:6334}")
    private int port;

    @Value("${vector.qdrant.collection:ms_tea_knowledge}")
    private String collection;

    private QdrantClient client;

    @PostConstruct
    public void init() {
        this.client = new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build());
        log.info("Qdrant 客户端已连接 {}:{}", host, port);
    }

    @PreDestroy
    public void close() {
        if (client != null) client.close();
    }

    @Override
    public void ensureCollection(int dimension) {
        try {
            boolean exists = client.listCollectionsAsync().get(5, TimeUnit.SECONDS)
                    .contains(collection);
            if (exists) {
                log.info("Qdrant 集合已存在: {}", collection);
                return;
            }
            client.createCollectionAsync(
                    Collections.CreateCollection.newBuilder()
                            .setCollectionName(collection)
                            // Cosine 距离，配合归一化后的 embedding
                            .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                                    .setParams(Collections.VectorParams.newBuilder()
                                            .setSize(dimension)
                                            .setDistance(Collections.Distance.Cosine)
                                            .build())
                                    .build())
                            .build()
            ).get(10, TimeUnit.SECONDS);
            log.info("Qdrant 集合创建成功: {} (dim={})", collection, dimension);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 集合初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        List<Points.PointStruct> structs = new ArrayList<>(points.size());
        for (VectorPoint p : points) {
            Map<String, Object> payload = new HashMap<>(p.payload() == null ? Map.of() : p.payload());
            payload.put("content", p.content());

            Points.PointStruct.Builder b = Points.PointStruct.newBuilder()
                    .setId(Points.PointId.newBuilder()
                            .setUuid(p.id() != null ? p.id() : UUID.randomUUID().toString()))
                    .setVectors(Points.Vectors.newBuilder()
                            .setVector(Points.Vector.newBuilder()
                                    .addAllData(toFloatList(p.vector()))
                                    .build())
                            .build());
            payload.forEach((k, v) -> b.putPayload(k, toValue(v)));
            structs.add(b.build());
        }
        try {
            client.upsertAsync(collection, structs).get(30, TimeUnit.SECONDS);
            log.debug("Qdrant 写入 {} 条", structs.size());
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 写入失败", e);
        }
    }

    @Override
    public List<SearchHit> search(float[] queryVector, int topK, Map<String, Object> filter) {
        try {
            Points.SearchPoints.Builder req = Points.SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .addAllVector(toFloatList(queryVector))
                    .setLimit(topK)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());

            List<Points.ScoredPoint> scored = client.searchAsync(req.build()).get(15, TimeUnit.SECONDS);

            List<SearchHit> hits = new ArrayList<>(scored.size());
            for (Points.ScoredPoint sp : scored) {
                Map<String, Object> payload = new HashMap<>();
                sp.getPayloadMap().forEach((k, v) -> payload.put(k, fromValue(v)));
                hits.add(new SearchHit(
                        sp.getId().getUuid(),
                        sp.getScore(),
                        String.valueOf(payload.getOrDefault("content", "")),
                        payload));
            }
            return hits;
        } catch (Exception e) {
            log.error("Qdrant 检索失败", e);
            return List.of();
        }
    }

    @Override
    public void dropCollection() {
        try {
            client.deleteCollectionAsync(collection).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("删除 Qdrant 集合失败: {}", e.getMessage());
        }
    }

    @Override
    public String backend() { return "qdrant"; }

    // ==================== 转换辅助 ====================

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private io.qdrant.client.grpc.JsonWithInt.Value toValue(Object v) {
        io.qdrant.client.grpc.JsonWithInt.Value.Builder b =
                io.qdrant.client.grpc.JsonWithInt.Value.newBuilder();
        if (v instanceof Integer i) b.setIntegerValue(i);
        else if (v instanceof Long l) b.setIntegerValue(l);
        else if (v instanceof Double d) b.setDoubleValue(d);
        else if (v instanceof Boolean bo) b.setBoolValue(bo);
        else b.setStringValue(String.valueOf(v));
        return b.build();
    }

    private Object fromValue(io.qdrant.client.grpc.JsonWithInt.Value v) {
        return switch (v.getKindCase()) {
            case INTEGER_VALUE -> v.getIntegerValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case BOOL_VALUE -> v.getBoolValue();
            case STRING_VALUE -> v.getStringValue();
            default -> null;
        };
    }
}
