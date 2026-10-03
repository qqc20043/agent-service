package com.guchen.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 手写 LLM 客户端（OpenAI 兼容协议）。
 *
 * 为什么不用 Spring AI：
 *  1) spring-ai-bom 1.0.0 GA 要求 Spring Boot 3.4+，与本项目 Boot 3.2.4 冲突；
 *  2) 手写才能讲清楚 SSE 分块解析、退避重试、token 计量 —— 这正是面试要问的。
 *
 * 覆盖能力：
 *  - 同步对话 / 流式对话（SSE 逐块解析）
 *  - 指数退避重试（仅对 5xx、超时、连接错误重试；4xx 不重试）
 *  - Function Calling（tools 参数 + tool_calls 解析）
 *  - token 用量统计（供成本计量落库）
 */
@Slf4j
@Component
public class LlmClient {

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.chat-model}")
    private String chatModel;

    @Value("${llm.max-retries:3}")
    private int maxRetries;

    @Value("${llm.retry-backoff-ms:800}")
    private long retryBackoffMs;

    /** 保留 baseUrl 供 /api/agent/llm-info 暴露运行态（只暴露主机名，不暴露 Key） */
    private final String baseUrl;

    public LlmClient(@Value("${llm.base-url}") String baseUrl,
                     @Value("${llm.api-key}") String apiKey,
                     @Value("${llm.timeout-seconds:60}") long timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                // 大模型首包可能很慢，读超时要给足
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    private final Duration timeout;

    // ==================== 同步对话 ====================

    /**
     * 非流式对话，返回完整文本。
     *
     * @param messages OpenAI 格式消息列表
     * @param tools    工具定义（可为 null）
     */
    public ChatResult chat(List<Message> messages, ArrayNode tools) {
        ObjectNode body = buildBody(messages, tools, false);

        JsonNode resp = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(retryBackoffMs))
                        .filter(this::isRetryable)
                        .doBeforeRetry(sig -> log.warn("LLM 调用失败，第 {} 次重试：{}",
                                sig.totalRetries() + 1, sig.failure().getMessage())))
                .block();

        return parseResult(resp);
    }

    // ==================== 流式对话（SSE） ====================

    /**
     * 流式对话。每个 token 片段通过 onDelta 回调吐出。
     *
     * SSE 数据格式（OpenAI 兼容）：
     *   data: {"choices":[{"delta":{"content":"你"}}]}
     *   data: {"choices":[{"delta":{"content":"好"}}]}
     *   data: [DONE]
     *
     * @param onDelta   每个增量片段的回调
     * @param onComplete 流结束回调，带累计 token 用量
     */
    public void chatStream(List<Message> messages,
                           ArrayNode tools,
                           Consumer<String> onDelta,
                           Consumer<TokenUsage> onComplete,
                           Consumer<Throwable> onError) {

        ObjectNode body = buildBody(messages, tools, true);

        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        StringBuilder full = new StringBuilder();

        Flux<String> lines = webClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);

        lines.timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(retryBackoffMs))
                        .filter(this::isRetryable))
                .subscribe(
                        chunk -> handleSseChunk(chunk, onDelta, full, promptTokens, completionTokens),
                        onError,
                        () -> {
                            log.debug("LLM 流式结束，累计输出 {} 字", full.length());
                            onComplete.accept(new TokenUsage(
                                    promptTokens.get(), completionTokens.get()));
                        }
                );
    }

    /** 解析单个 SSE 分片。兼容带/不带 "data: " 前缀两种返回。 */
    private void handleSseChunk(String chunk, Consumer<String> onDelta,
                                StringBuilder full,
                                AtomicInteger promptTokens,
                                AtomicInteger completionTokens) {
        for (String raw : chunk.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("data:")) line = line.substring(5).trim();
            if ("[DONE]".equals(line)) continue;

            try {
                JsonNode node = mapper.readTree(line);

                // 用量信息可能在最后一块
                JsonNode usage = node.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    promptTokens.set(usage.path("prompt_tokens").asInt(0));
                    completionTokens.set(usage.path("completion_tokens").asInt(0));
                }

                JsonNode delta = node.path("choices").path(0).path("delta");
                // 兼容 reasoning_content（DeepSeek-R1 类模型）与 content
                String piece = delta.path("content").asText("");
                if (piece.isEmpty()) piece = delta.path("reasoning_content").asText("");
                if (!piece.isEmpty()) {
                    full.append(piece);
                    onDelta.accept(piece);
                }

                // 流式下 tool_calls 是分片拼装的，这里只做日志，完整解析在非流式路径
                if (delta.has("tool_calls")) {
                    log.debug("检测到流式 tool_calls 分片");
                }
            } catch (Exception e) {
                log.trace("跳过无法解析的 SSE 分片: {}", line);
            }
        }
    }

    // ==================== 流式分片重组（绕网关 FC 缺陷） ====================

    /**
     * 流式调用 + 完整重组，返回与非流式 chat() 相同的 ChatResult。
     *
     * 背景（2026-10-04 网关审计）：自建网关在【非流式】模式下会丢弃 tool_calls
     * （只回 finish_reason=tool_calls，message 里数组为空），但【流式】模式下
     * delta.tool_calls 分片按标准 OpenAI 协议完整透传（首片带 id+name，
     * 后续片按 index 追加 arguments）。因此 ReAct 循环改走本方法：
     * 内部消费 SSE 流并重组出完整的工具调用，对外语义与 chat() 一致。
     */
    public ChatResult chatViaStream(List<Message> messages, ArrayNode tools) {
        ObjectNode body = buildBody(messages, tools, true);

        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        AtomicReference<String> finishReason = new AtomicReference<>("stop");
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallAssembler> assemblers = new java.util.HashMap<>();

        webClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(retryBackoffMs))
                        .filter(this::isRetryable))
                .doOnNext(chunk -> assembleStreamChunk(
                        chunk, content, assemblers, promptTokens, completionTokens, finishReason))
                .blockLast();

        List<ToolCall> toolCalls = assemblers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue().build())
                .toList();

        log.debug("流式重组完成：content {} 字，tool_calls {} 个", content.length(), toolCalls.size());
        return new ChatResult(content.toString(), toolCalls,
                new TokenUsage(promptTokens.get(), completionTokens.get()), finishReason.get());
    }

    /** 解析单个 SSE 分片并就地重组（内容拼接 / 工具分片聚合 / 用量与结束原因）。 */
    private void assembleStreamChunk(String chunk, StringBuilder content,
                                     Map<Integer, ToolCallAssembler> assemblers,
                                     AtomicInteger promptTokens, AtomicInteger completionTokens,
                                     AtomicReference<String> finishReason) {
        for (String raw : chunk.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("data:")) line = line.substring(5).trim();
            if (line.isEmpty() || "[DONE]".equals(line)) continue;

            try {
                JsonNode node = mapper.readTree(line);

                JsonNode usage = node.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    promptTokens.set(usage.path("prompt_tokens").asInt(0));
                    completionTokens.set(usage.path("completion_tokens").asInt(0));
                }

                JsonNode choice = node.path("choices").path(0);
                String fr = choice.path("finish_reason").asText("");
                if (!fr.isEmpty()) finishReason.set(fr);

                JsonNode delta = choice.path("delta");
                String piece = delta.path("content").asText("");
                if (!piece.isEmpty()) content.append(piece);

                JsonNode tcs = delta.path("tool_calls");
                if (tcs.isArray()) {
                    for (JsonNode tc : tcs) {
                        int index = tc.path("index").asInt(0);
                        assemblers.computeIfAbsent(index, k -> new ToolCallAssembler()).merge(tc);
                    }
                }
            } catch (Exception e) {
                log.trace("跳过无法解析的 SSE 分片: {}", line);
            }
        }
    }

    /** 单个工具调用的分片聚合器：首片携带 id/name，后续片按 index 追加 arguments。 */
    private static class ToolCallAssembler {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(JsonNode tc) {
            if (id == null && !tc.path("id").asText("").isEmpty()) id = tc.path("id").asText();
            String n = tc.path("function").path("name").asText("");
            if (!n.isEmpty()) name = n;
            arguments.append(tc.path("function").path("arguments").asText(""));
        }

        ToolCall build() {
            return new ToolCall(id != null ? id : "call_" + System.nanoTime(),
                    name != null ? name : "unknown", arguments.toString());
        }
    }

    // ==================== 内部工具 ====================

    private ObjectNode buildBody(List<Message> messages, ArrayNode tools, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        body.put("stream", stream);
        body.put("temperature", 0.3);   // Agent 场景要稳定，不宜高

        ArrayNode arr = body.putArray("messages");
        for (Message m : messages) {
            ObjectNode node = arr.addObject();
            node.put("role", m.role());
            node.put("content", m.content());
            if (m.toolCallId() != null) node.put("tool_call_id", m.toolCallId());
            if (m.name() != null) node.put("name", m.name());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) node.set("tool_calls", m.toolCalls());
        }

        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private ChatResult parseResult(JsonNode resp) {
        if (resp == null) throw new LlmException("LLM 返回为空");

        JsonNode choice = resp.path("choices").path(0);
        JsonNode msg = choice.path("message");
        String content = msg.path("content").asText("");

        JsonNode usage = resp.path("usage");
        TokenUsage tokenUsage = new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));

        // 解析 tool_calls
        List<ToolCall> toolCalls = new java.util.ArrayList<>();
        JsonNode tc = msg.path("tool_calls");
        if (tc.isArray()) {
            for (JsonNode one : tc) {
                toolCalls.add(new ToolCall(
                        one.path("id").asText(),
                        one.path("function").path("name").asText(),
                        one.path("function").path("arguments").asText("{}")));
            }
        }

        return new ChatResult(content, toolCalls, tokenUsage,
                choice.path("finish_reason").asText("stop"));
    }

    /** 只对可恢复错误重试：超时、连接异常、5xx。4xx（鉴权/参数错）重试无意义。 */
    private boolean isRetryable(Throwable t) {
        if (t instanceof java.util.concurrent.TimeoutException) return true;
        if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException w) {
            return w.getStatusCode().is5xxServerError() || w.getStatusCode().value() == 429;
        }
        return true; // 连接层异常
    }

    // ==================== 运行态自描述 ====================

    /**
     * 暴露当前生效的 LLM 配置，供前端徽章展示与排障。
     * <p><b>刻意只返回 baseUrl 的 host，绝不返回 apiKey</b>——这个端点若部署到公网，
     * 泄露 Key 就是真实的金钱损失（见需求汇总 §6.3 安全红线）。
     */
    public java.util.Map<String, Object> describe() {
        String host = baseUrl;
        boolean mock = false;
        try {
            java.net.URI u = java.net.URI.create(baseUrl);
            host = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            // 本地回环 = Mock/自测环境，前端据此显示 Mock 标记
            mock = "127.0.0.1".equals(u.getHost()) || "localhost".equalsIgnoreCase(u.getHost());
        } catch (Exception ignored) {
            // baseUrl 非法时原样返回，便于发现配置错误
        }
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("model", chatModel);
        m.put("endpoint", host);
        m.put("mock", mock);
        m.put("maxRetries", maxRetries);
        return m;
    }

    // ==================== 数据载体 ====================

    public record Message(String role, String content, String toolCallId, String name,
                          ArrayNode toolCalls) {
        public static Message system(String c) { return new Message("system", c, null, null, null); }
        public static Message user(String c) { return new Message("user", c, null, null, null); }
        public static Message assistant(String c) { return new Message("assistant", c, null, null, null); }
        /** 带工具调用的 assistant 消息：回传 tool 结果时协议要求原样携带 tool_calls */
        public static Message assistantWithToolCalls(String c, ArrayNode toolCalls) {
            return new Message("assistant", c, null, null, toolCalls);
        }
        public static Message tool(String toolCallId, String name, String content) {
            return new Message("tool", content, toolCallId, name, null);
        }

        /** 把重组出的工具调用列表序列化为请求体里的 tool_calls 数组 */
        public static ArrayNode toolCallsToArray(ObjectMapper mapper, List<ToolCall> calls) {
            ArrayNode arr = mapper.createArrayNode();
            for (ToolCall c : calls) {
                ObjectNode one = arr.addObject();
                one.put("id", c.id());
                one.put("type", "function");
                one.putObject("function")
                        .put("name", c.name())
                        .put("arguments", c.arguments());
            }
            return arr;
        }
    }

    public record ToolCall(String id, String name, String arguments) {}

    public record TokenUsage(int promptTokens, int completionTokens) {
        public int total() { return promptTokens + completionTokens; }
    }

    public record ChatResult(String content, List<ToolCall> toolCalls,
                             TokenUsage usage, String finishReason) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String msg) { super(msg); }
    }
}
