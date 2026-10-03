package com.guchen.agent.controller;

import com.guchen.agent.agent.AgentOrchestrator;
import com.guchen.agent.memory.SessionMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 对话入口 —— SSE 流式输出。
 *
 * SSE vs WebSocket（面试必问，这里就是答案）：
 *  - 本场景是「客户端问一句 → 服务端持续推」的单向流，SSE 足够，且是纯 HTTP，无需握手升级；
 *  - SSE 自带断线重连（EventSource），WebSocket 要自己实现心跳与重连；
 *  - SSE 走 HTTP/2 时可多路复用，且天然过网关/负载均衡（WebSocket 需要额外配置 Upgrade 头）；
 *  - 需要双向实时（如协同编辑、实时语音）才选 WebSocket。
 *
 * 实现要点：
 *  1. 业务逻辑丢到独立线程池，避免占用 Tomcat 请求线程；
 *  2. 定期发心跳注释行，防止 Nginx/网关因空闲超时切断连接；
 *  3. 结束时显式 complete()，异常时 completeWithError()。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentOrchestrator orchestrator;
    private final SessionMemoryService memoryService;

    /** 专用线程池：Agent 任务可能跑十几秒，不能占 Tomcat 线程 */
    private final ExecutorService agentPool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "agent-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 流式对话。
     *
     * curl 验证：
     *   curl -N -H "token: YOUR_TOKEN" -H "Content-Type: application/json" \
     *        -d '{"message":"有哪些绿茶推荐","sessionId":"s1"}' \
     *        http://localhost:8081/api/agent/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req, HttpServletRequest httpReq) {

        String sessionId = req.sessionId() != null && !req.sessionId().isBlank()
                ? req.sessionId() : UUID.randomUUID().toString();
        String token = httpReq.getHeader("token");

        // 0L = 不超时，由业务侧控制生命周期
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);

        emitter.onCompletion(() -> { closed.set(true); log.debug("SSE 完成 session={}", sessionId); });
        emitter.onTimeout(() -> { closed.set(true); log.warn("SSE 超时 session={}", sessionId); });
        emitter.onError(e -> { closed.set(true); log.warn("SSE 异常 session={}", sessionId, e); });

        agentPool.submit(() -> {
            try {
                send(emitter, closed, "meta", Map.of("sessionId", sessionId));

                orchestrator.run(
                        sessionId,
                        req.message(),
                        token,
                        // 每个 token 片段
                        delta -> send(emitter, closed, "delta", Map.of("content", delta)),
                        // 工具调用事件（前端可展示「正在查询商品…」）
                        toolEvent -> send(emitter, closed, "tool", toolEvent),
                        // 结束
                        summary -> {
                            send(emitter, closed, "done", summary);
                            if (closed.compareAndSet(false, true)) emitter.complete();
                        },
                        err -> {
                            send(emitter, closed, "error",
                                    Map.of("message", String.valueOf(err.getMessage())));
                            if (closed.compareAndSet(false, true)) emitter.completeWithError(err);
                        }
                );
            } catch (Exception e) {
                log.error("Agent 执行失败 session={}", sessionId, e);
                if (closed.compareAndSet(false, true)) emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /** 非流式接口，便于前端降级与压测。 */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req, HttpServletRequest httpReq) {
        String sessionId = req.sessionId() != null ? req.sessionId() : UUID.randomUUID().toString();
        StringBuilder sb = new StringBuilder();
        orchestrator.run(sessionId, req.message(), httpReq.getHeader("token"),
                sb::append, e -> {}, s -> {}, e -> {});
        return Map.of("sessionId", sessionId, "answer", sb.toString());
    }

    /** 清空会话记忆 */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> clear(@PathVariable String sessionId) {
        memoryService.clear(sessionId);
        return Map.of("cleared", true, "sessionId", sessionId);
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开，属正常情况
            closed.set(true);
        }
    }

    public record ChatRequest(String message, String sessionId) {}
}
