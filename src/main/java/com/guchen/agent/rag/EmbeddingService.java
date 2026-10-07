package com.guchen.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 服务：调用硅基流动的 BAAI/bge-m3（OpenAI 兼容 /v1/embeddings 协议）。
 *
 * 为什么选硅基流动（三选一决策，2026-10-07 用户拍板）：
 *  - 用户自建网关只有聊天模型 deepseek-v4.1-flash，DeepSeek 官方无 embedding 产品；
 *  - BGE-M3 是当前中文语义检索的主流开源模型，硅基流动有免费额度；
 *  - 后续可把硅基流动作为渠道挂进用户自建网关，统一入口（本服务的 base-url 可配置）。
 *
 * 约定：单条与批量共用一个接口（批量用于入库，减少网络往返）；
 * 调用失败返回空列表，由调用方降级为纯 BM25（HybridRetriever.embed 契约）。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.embedding-model:BAAI/bge-m3}")
    private String model;

    public EmbeddingService(@Value("${llm.embedding-base-url:https://api.siliconflow.cn}") String baseUrl,
                            @Value("${llm.embedding-api-key:}") String apiKey) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 单条向量化；失败返回 null（调用方降级为纯 BM25）。 */
    public float[] embedOne(String text) {
        List<float[]> r = embed(List.of(text));
        return r.isEmpty() ? null : r.get(0);
    }

    /** 批量向量化（入库用）；失败返回空列表。向量维度与 BAAI/bge-m3 一致（1024）。 */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);
            body.put("encoding_format", "float");

            JsonNode resp = webClient.post()
                    .uri("/v1/embeddings")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(java.time.Duration.ofSeconds(30));

            JsonNode data = resp.path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                log.warn("embedding 返回数量不匹配：期望 {}，实际 {}", texts.size(), data.size());
                return List.of();
            }
            // OpenAI 协议按 index 对齐输入顺序
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                JsonNode vec = data.get(i).path("embedding");
                float[] v = new float[vec.size()];
                for (int j = 0; j < vec.size(); j++) v[j] = (float) vec.get(j).asDouble();
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("embedding 调用失败（降级为纯 BM25）: {}", e.getMessage());
            return List.of();
        }
    }
}
