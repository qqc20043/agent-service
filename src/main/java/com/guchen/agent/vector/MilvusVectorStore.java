package com.guchen.agent.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Milvus 实现 —— 对比基准向量库（默认关闭）。
 *
 * 打开方式：application.yaml 里 vector.milvus.enabled=true，
 * 或在 Nacos 里动态改（体现配置中心价值）。
 *
 * 为什么只作对比不作主链路：
 *  - Milvus standalone 需要 etcd + minio + milvus 三个容器，约 4GB+ 内存；
 *  - 对漫思茶这个数据量级（几千条 chunk）属于过度设计；
 *  - 但它的分片/水平扩展能力是 Qdrant 单机比不了的，规模上到千万级时会换回来。
 *  —— 这段取舍就是面试要的答案。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vector.milvus.enabled", havingValue = "true")
public class MilvusVectorStore implements VectorStore {

    @Value("${vector.milvus.host:127.0.0.1}")
    private String host;

    @Value("${vector.milvus.port:19530}")
    private int port;

    @Value("${vector.milvus.collection:ms_tea_knowledge}")
    private String collection;

    private MilvusServiceClient client;
    private int dimension = 1024;

    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "embedding";
    private static final String FIELD_CONTENT = "content";

    @PostConstruct
    public void init() {
        this.client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .withConnectTimeout(10, TimeUnit.SECONDS)
                        .build());
        log.info("Milvus 客户端已连接 {}:{}", host, port);
    }

    @PreDestroy
    public void close() {
        if (client != null) client.close();
    }

    @Override
    public void ensureCollection(int dimension) {
        this.dimension = dimension;

        R<Boolean> has = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        if (Boolean.TRUE.equals(has.getData())) {
            log.info("Milvus 集合已存在: {}", collection);
            return;
        }

        FieldType idField = FieldType.newBuilder()
                .withName(FIELD_ID)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName(FIELD_VECTOR)
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName(FIELD_CONTENT)
                .withDataType(DataType.VarChar)
                .withMaxLength(8192)
                .build();

        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collection)
                .withShardsNum(2)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .build());

        // HNSW 索引，与 Qdrant 默认索引对齐，保证对比公平
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName(FIELD_VECTOR)
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                .build());

        client.loadCollection(io.milvus.param.collection.LoadCollectionParam.newBuilder()
                .withCollectionName(collection).build());

        log.info("Milvus 集合创建完成: {} (dim={})", collection, dimension);
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        List<String> ids = new ArrayList<>(points.size());
        List<List<Float>> vectors = new ArrayList<>(points.size());
        List<String> contents = new ArrayList<>(points.size());

        for (VectorPoint p : points) {
            ids.add(p.id() != null ? p.id() : UUID.randomUUID().toString());
            List<Float> vec = new ArrayList<>(p.vector().length);
            for (float f : p.vector()) vec.add(f);
            vectors.add(vec);
            contents.add(p.content() == null ? "" : p.content().substring(0, Math.min(8000, p.content().length())));
        }

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field(FIELD_ID, ids),
                new InsertParam.Field(FIELD_VECTOR, vectors),
                new InsertParam.Field(FIELD_CONTENT, contents));

        R<MutationResult> result = client.insert(InsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(fields)
                .build());

        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus 写入失败: " + result.getMessage());
        }
        // 插入后 flush 才能被检索到（Milvus 的写入可见性模型，与 Qdrant 不同）
        client.flush(io.milvus.param.collection.FlushParam.newBuilder()
                .withCollectionNames(List.of(collection)).build());
        log.debug("Milvus 写入 {} 条", points.size());
    }

    @Override
    public List<SearchHit> search(float[] queryVector, int topK, Map<String, Object> filter) {
        List<Float> vec = new ArrayList<>(queryVector.length);
        for (float f : queryVector) vec.add(f);

        R<SearchResults> resp = client.search(SearchParam.newBuilder()
                .withCollectionName(collection)
                .withMetricType(MetricType.COSINE)
                .withTopK(topK)
                .withVectors(List.of(vec))
                .withVectorFieldName(FIELD_VECTOR)
                .withOutFields(List.of(FIELD_CONTENT))
                .withParams("{\"ef\":128}")
                .build());

        if (resp.getStatus() != R.Status.Success.getCode()) {
            log.error("Milvus 检索失败: {}", resp.getMessage());
            return List.of();
        }

        List<SearchHit> hits = new ArrayList<>();
        var results = resp.getData().getResults();
        for (int i = 0; i < results.getIds().getIntId().getDataCount(); i++) {
            String id = results.getIds().getIntId().getData(i) > 0
                    ? String.valueOf(results.getIds().getIntId().getData(i))
                    : "";
            float score = results.getScores(i);
            String content = results.getFieldsData(0).getScalars().getStringData().getData(i);
            hits.add(new SearchHit(id, score, content, Map.of()));
        }
        return hits;
    }

    @Override
    public void dropCollection() {
        client.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(collection).build());
    }

    @Override
    public String backend() { return "milvus"; }
}
