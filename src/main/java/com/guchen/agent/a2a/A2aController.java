package com.guchen.agent.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A (Agent-to-Agent) 最小实现。
 *
 * A2A 与 MCP 的区别（高频面试题，务必分清）：
 *   - **MCP**：LLM 应用 ←→ **工具/数据源**（纵向：Agent 调用能力）
 *   - **A2A**：Agent ←→ **Agent**（横向：Agent 之间协作，一个 Agent 把子任务委托给另一个）
 *
 * A2A 核心概念：
 *   - Agent Card：描述「我是谁、我能做什么、怎么调用我」，发布在 /.well-known/agent.json
 *   - Task：一次委托的执行单元，有 submitted/working/completed/failed 状态机
 *
 * 本实现只覆盖最小可用集（agent card + tasks/send + tasks/get），
 * 足以演示「漫思茶 Agent 把『茶知识问答』委托给专门的 RAG Agent」这一场景。
 * 官方 Java SDK 尚不成熟，手写可完全掌控协议细节。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class A2aController {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${a2a.enabled:true}")
    private boolean enabled;

    @Value("${server.port:8081}")
    private int port;

    /** 内存任务表（生产应落库，此处为演示最小实现） */
    private final Map<String, ObjectNode> taskStore = new ConcurrentHashMap<>();

    // ==================== Agent Card ====================

    /**
     * Agent Card —— A2A 的服务发现入口。
     * 其他 Agent 先拉这个，才知道怎么调用你。
     *
     * 验证：curl http://localhost:8081/.well-known/agent.json
     */
    @GetMapping(value = "${a2a.agent-card-path:/.well-known/agent.json}",
                produces = "application/json")
    public ObjectNode agentCard() {
        ObjectNode card = mapper.createObjectNode();
        card.put("name", "mansicha-tea-agent");
        card.put("description", "漫思茶电商平台的茶饮顾问 Agent，可查询商品、回答茶知识、协助下单");
        card.put("version", "1.0.0");
        card.put("url", "http://localhost:" + port + "/a2a");

        // 能力声明
        ObjectNode capabilities = card.putObject("capabilities");
        capabilities.put("streaming", true);
        capabilities.put("pushNotifications", false);
        capabilities.put("stateTransitionHistory", false);

        // 技能清单
        ArrayNode skills = card.putArray("skills");
        skills.add(skill("tea_search", "茶叶商品搜索", "按关键词/分类/主题搜索茶叶商品"));
        skills.add(skill("tea_knowledge", "茶知识问答", "回答茶叶冲泡、保存、工艺等知识问题"));
        skills.add(skill("order_assist", "下单协助", "帮助用户加购与创建订单"));

        // 入参/出参模式
        ObjectNode schemas = card.putObject("defaultInputModes");
        schemas.putArray("text/plain");
        ObjectNode out = card.putObject("defaultOutputModes");
        out.putArray("text/plain");

        return card;
    }

    private ObjectNode skill(String id, String name, String desc) {
        ObjectNode s = mapper.createObjectNode();
        s.put("id", id);
        s.put("name", name);
        s.put("description", desc);
        return s;
    }

    // ==================== JSON-RPC 入口 ====================

    /**
     * A2A 的 tasks/send 与 tasks/get。
     *
     * 验证（模拟另一个 Agent 委托任务）：
     *   curl -X POST http://localhost:8081/a2a -H "Content-Type: application/json" -d '{
     *     "jsonrpc":"2.0","id":1,"method":"tasks/send",
     *     "params":{"id":"t1","message":{"role":"user",
     *       "parts":[{"type":"text","text":"龙井和碧螺春有什么区别"}]}}}'
     */
    @PostMapping(value = "/a2a", consumes = "application/json", produces = "application/json")
    public ObjectNode handle(@RequestBody JsonNode request) {
        if (!enabled) {
            return error(request.path("id"), -32000, "A2A 未启用");
        }

        String method = request.path("method").asText("");
        JsonNode id = request.path("id");
        JsonNode params = request.path("params");

        return switch (method) {
            case "tasks/send" -> handleSend(id, params);
            case "tasks/get" -> handleGet(id, params);
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    /**
     * 接收委托任务。真实场景下这里会把任务交给 AgentOrchestrator 执行，
     * 由于 A2A 支持异步，先返回 working 状态，客户端再轮询 tasks/get。
     */
    private ObjectNode handleSend(JsonNode id, JsonNode params) {
        String taskId = params.path("id").asText(UUID.randomUUID().toString());

        // 提取用户文本
        String text = "";
        JsonNode parts = params.path("message").path("parts");
        if (parts.isArray()) {
            for (JsonNode p : parts) {
                if ("text".equals(p.path("type").asText())) {
                    text = p.path("text").asText();
                    break;
                }
            }
        }

        log.info("收到 A2A 任务 taskId={}, text={}", taskId, text);

        ObjectNode task = mapper.createObjectNode();
        task.put("id", taskId);
        task.put("sessionId", params.path("sessionId").asText(taskId));
        task.put("status", "completed");     // 最小实现：同步完成
        task.put("createdAt", Instant.now().toString());

        ObjectNode message = task.putObject("message");
        message.put("role", "agent");
        ArrayNode partsOut = message.putArray("parts");
        ObjectNode textPart = partsOut.addObject();
        textPart.put("type", "text");
        // TODO 接入 AgentOrchestrator.run() 产出真实回答
        textPart.put("text", "（此处为 Agent 委托执行结果，接入 AgentOrchestrator 后返回真实回答）");

        taskStore.put(taskId, task);

        ObjectNode result = mapper.createObjectNode();
        result.set("task", task);
        return ok(id, result);
    }

    private ObjectNode handleGet(JsonNode id, JsonNode params) {
        String taskId = params.path("id").asText();
        ObjectNode task = taskStore.get(taskId);
        if (task == null) {
            return error(id, -32001, "Task not found: " + taskId);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("task", task);
        return ok(id, result);
    }

    // ==================== 包装 ====================

    private ObjectNode ok(JsonNode id, ObjectNode result) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id.isMissingNode() ? mapper.nullNode() : id);
        resp.set("result", result);
        return resp;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id.isMissingNode() ? mapper.nullNode() : id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return resp;
    }
}
