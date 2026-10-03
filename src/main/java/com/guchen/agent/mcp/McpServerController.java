package com.guchen.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guchen.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) Server —— 把漫思茶工具暴露给任意 MCP 客户端。
 *
 * MCP 解决什么问题（面试必答）：
 *   在没有 MCP 之前，每接一个 LLM 应用就要为它重写一遍工具适配层（M×N 问题）。
 *   MCP 把「工具/资源/提示词」标准化成 JSON-RPC 2.0 协议，
 *   LLM 应用（Client）与能力提供方（Server）解耦，一次实现处处可用。
 *   类比：MCP 之于 AI 工具 ≈ LSP 之于编辑器语言支持。
 *
 * 本实现覆盖 MCP 三个核心方法：
 *   - initialize            握手，返回能力声明
 *   - tools/list            列出可用工具及其 JSON Schema
 *   - tools/call            执行工具
 *
 * 说明：MCP 官方 Java SDK 仍在快速迭代，这里手写最小可用实现，
 * 好处是协议细节完全可控、便于讲清楚，也避免被 SDK 版本变更卡住。
 */
@Slf4j
@RestController
@RequestMapping("${mcp.endpoint-path:/mcp}")
@RequiredArgsConstructor
public class McpServerController {

    private final ToolRegistry tools;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${mcp.server-name:mansicha-tea-tools}")
    private String serverName;

    private static final String PROTOCOL_VERSION = "2024-11-05";

    /**
     * MCP 单一入口，JSON-RPC 2.0。
     *
     * 验证：
     *   curl -X POST http://localhost:8081/mcp -H "Content-Type: application/json" \
     *     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode handle(@RequestBody JsonNode request) {
        String method = request.path("method").asText("");
        JsonNode id = request.path("id");
        JsonNode params = request.path("params");

        log.debug("MCP 请求: {}", method);

        return switch (method) {
            case "initialize" -> ok(id, handleInitialize());
            case "tools/list" -> ok(id, handleToolsList());
            case "tools/call" -> ok(id, handleToolsCall(params));
            case "notifications/initialized" -> ok(id, mapper.createObjectNode());
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    /** MCP 握手：声明 server 信息与能力。 */
    private ObjectNode handleInitialize() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", serverName);
        serverInfo.put("version", "1.0.0");

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");   // 本 server 只提供工具能力

        return result;
    }

    /** 列出工具：直接复用 ToolRegistry 的 schema，保证 MCP 与 Function Calling 单一数据源。 */
    private ObjectNode handleToolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolArr = result.putArray("tools");

        for (String name : tools.allToolNames()) {
            ToolRegistry.ToolDefinition def = tools.get(name);
            if (def == null) continue;

            ObjectNode t = toolArr.addObject();
            t.put("name", def.name());
            t.put("description", def.description());
            t.set("inputSchema", def.parameters());
        }
        return result;
    }

    /** 执行工具：返回 MCP 规范的 content 数组。 */
    private ObjectNode handleToolsCall(JsonNode params) {
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");

        String output = tools.execute(name, arguments.toString());

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", output);

        result.put("isError", output.contains("\"error\""));
        return result;
    }

    // ==================== JSON-RPC 包装 ====================

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
