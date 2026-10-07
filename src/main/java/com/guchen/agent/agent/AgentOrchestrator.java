package com.guchen.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.guchen.agent.client.FeignAuthConfig;
import com.guchen.agent.llm.LlmClient;
import com.guchen.agent.llm.LlmClient.ChatResult;
import com.guchen.agent.llm.LlmClient.Message;
import com.guchen.agent.llm.LlmClient.ToolCall;
import com.guchen.agent.memory.SessionMemoryService;
import com.guchen.agent.rag.HybridRetriever;
import com.guchen.agent.trace.AgentTraceService;
import com.guchen.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Agent 编排器：确定性主干 + 局部自主决策。
 *
 * 为什么不做「全自主 Agent」（面试必问的取舍题）：
 *  - 电商场景下「下单/兑换券」是有副作用的写操作，全自主决策会带来不可控的资金风险；
 *  - 全自主循环容易陷入死循环、token 成本不可预测；
 *  - 因此采用：**意图路由（确定性）→ 工具调用（受控自主）→ 生成（确定性收口）**，
 *    并给自主部分加硬上限（maxIterations / maxToolCallsPerTurn）。
 *
 * 这个 ReAct 循环本身是手写的（不依赖框架），
 * 复杂流程编排在 AgentGraphConfig 里用 LangGraph4j 的 DAG 表达。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final HybridRetriever retriever;
    private final SessionMemoryService memory;
    private final AgentTraceService trace;
    private final ObjectMapper mapper;
    private final com.guchen.agent.memory.UserContextService userContext;
    private final com.guchen.agent.memory.UserProfileService userProfile;

    @Value("${agent.max-iterations:6}")
    private int maxIterations;

    @Value("${agent.max-tool-calls-per-turn:5}")
    private int maxToolCallsPerTurn;

    /**
     * 确认/取消协议句式（不经 LLM 的直通消息）：
     * 「确认下单，令牌 <32位hex>」「取消订单，令牌 <32位hex>」
     */
    private static final java.util.regex.Pattern CONFIRM_MSG_PATTERN =
            java.util.regex.Pattern.compile("(?:确认下单|取消订单)[，,]?\s*令牌\s*([a-f0-9]{16,64})");

    private static final String SYSTEM_PROMPT = """
            你是「漫思茶」电商平台的智能茶饮顾问，负责帮用户找茶、问茶、下单。

            工作原则：
            1. 需要商品数据时必须调用工具获取真实数据，禁止编造价格、库存、商品名。
            2. 涉及下单、加入购物车、兑换优惠券等写操作，先向用户复述确认，再执行。
            3. 回答茶知识问题时，优先使用检索到的知识片段，并在末尾标注引用来源。
            4. 工具返回 degraded=true 表示后端服务不可用，如实告知用户稍后重试，不要编造结果。
            5. 回答简洁专业，不啰嗦，不使用夸张营销话术。
            6. 下单必须走确认流：先调用 prepare_order 拟单，把拟单内容（商品/规格/数量/合计/收货地址）
               完整复述给用户，等用户明确回复同意后才调用 confirm_order。用户未确认绝不 confirm；
               金额与商品信息一律以工具返回为准，禁止自行计算或编造价格。
               用户回复「确认」并附令牌时，只调用 confirm_order(该令牌)，绝不重新拟单或执行其他工具。
            7. 用户在对话里直接报收货人/电话/地址时（如「收货人 qq，电话 182...，地址南昌大学」），
               调用 add_address 替用户代填，然后把新地址用于拟单，不要让用户去 App 里手动添加。
            """;

    /**
     * 执行一轮完整对话。
     *
     * @param onDelta    流式输出回调
     * @param onToolEvent 工具调用事件回调
     * @param onDone     完成回调，携带统计信息
     * @param onError    错误回调
     */
    public void run(String sessionId,
                    String userInput,
                    String token,
                    Consumer<String> onDelta,
                    Consumer<Map<String, Object>> onToolEvent,
                    Consumer<Map<String, Object>> onDone,
                    Consumer<Throwable> onError) {

        String traceId = UUID.randomUUID().toString().replace("-", "");
        long start = System.currentTimeMillis();
        int totalPromptTokens = 0, totalCompletionTokens = 0, toolCallCount = 0;

        try {
            // token 透传给 Feign，让老项目能做鉴权
            FeignAuthConfig.setToken(token);

            // ---------- 0. 用户身份与记忆归属 ----------
            // 登录用户 → 记忆域按 userId（跨会话/跨设备），并注入订单画像；
            // 未登录/token 失效 → 退回会话级（匿名），画像为空不影响流程
            Long userId = userContext.resolveUserId(token);
            String memoryScope = (userId != null) ? ("u" + userId) : ("s" + sessionId);
            String profileLine = (userId != null) ? userProfile.profileContext(token, userId) : "";
            log.debug("[{}] 记忆域: {} (userId={})", traceId, memoryScope, userId);

            // ---------- 确认/取消协议直通（不经 LLM） ----------
            // 「确认下单，令牌 X」/「取消订单，令牌 X」是刚性协议步骤：执行结果完全确定，
            // 走 LLM 会出现「重新研究一遍再拟单」的循环（实测踩中），且白耗 token。
            java.util.regex.Matcher cm = CONFIRM_MSG_PATTERN.matcher(userInput == null ? "" : userInput.trim());
            if (cm.find()) {
                boolean cancel = userInput.contains("取消");
                String tok = cm.group(1);
                onToolEvent.accept(Map.of("type", "confirm_executing", "cancel", cancel));
                String output = tools.execute(cancel ? "cancel_order" : "confirm_order",
                        "{\"confirmToken\":\"" + tok + "\"}");
                trace.recordToolCall(traceId, cancel ? "cancel_order" : "confirm_order",
                        "{\"confirmToken\":\"" + tok + "\"}", 0, true);
                for (String piece : splitForStream(output)) {
                    onDelta.accept(piece);
                    sleep(12);
                }
                memory.append(memoryScope, userInput, output);
                onDone.accept(Map.of("traceId", traceId, "sessionId", sessionId,
                        "toolCalls", 1, "promptTokens", 0, "completionTokens", 0,
                        "latencyMs", (int) (System.currentTimeMillis() - start), "costYuan", 0.0));
                return;
            }

            // ---------- 1. 意图路由（确定性） ----------
            List<String> candidateTools = routeIntent(userInput);
            ArrayNode toolsSchema = tools.buildToolsSchema(candidateTools);
            log.debug("[{}] 意图路由命中工具: {}", traceId, candidateTools);

            // ---------- 2. 组装上下文 ----------
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));

            // 用户画像（来自真实订单，登录用户专属）
            if (!profileLine.isBlank()) {
                messages.add(Message.system("已核实的用户画像（来自其真实订单记录，推荐时可引用）：\n" + profileLine));
            }

            // RAG：茶知识检索（仅在疑似知识类问题时触发，省 token）
            if (needsKnowledge(userInput)) {
                String knowledge = retriever.retrieveAsContext(userInput, 5);
                if (!knowledge.isBlank()) {
                    messages.add(Message.system("以下是茶知识库检索结果，请优先依据它回答：\n" + knowledge));
                    onToolEvent.accept(Map.of("type", "rag", "hits", knowledge.length()));
                }
            }

            // 多轮记忆（登录用户跨会话共享）
            messages.addAll(memory.loadHistory(memoryScope));
            messages.add(Message.user(userInput));

            // ---------- 3. ReAct 循环（受控自主） ----------
            String finalAnswer = null;
            boolean authExpired = false;
            for (int i = 0; i < maxIterations; i++) {
                // 走流式分片重组而非非流式：网关非流式会丢 tool_calls（见 LlmClient#chatViaStream）
                ChatResult result = llm.chatViaStream(messages, toolsSchema);
                totalPromptTokens += result.usage().promptTokens();
                totalCompletionTokens += result.usage().completionTokens();

                if (!result.hasToolCalls()) {
                    finalAnswer = result.content();
                    break;
                }

                // 写操作硬上限：超限则强制收口，避免成本失控
                if (toolCallCount + result.toolCalls().size() > maxToolCallsPerTurn) {
                    log.warn("[{}] 工具调用超出上限，强制收口", traceId);
                    finalAnswer = "本次请求需要执行较多操作，请拆分后再试。";
                    break;
                }

                // 协议要求：回传 tool 结果时，前置 assistant 消息必须原样携带 tool_calls，
                // 否则第二轮请求会被网关/上游以 400 拒绝（骨架首轮真实工具链暴露的 bug）
                messages.add(result.hasToolCalls()
                        ? Message.assistantWithToolCalls(result.content(),
                                Message.toolCallsToArray(mapper, result.toolCalls()))
                        : Message.assistant(result.content()));

                for (ToolCall call : result.toolCalls()) {
                    toolCallCount++;
                    boolean isWrite = tools.isWriteOperation(call.name());
                    onToolEvent.accept(Map.of(
                            "type", "tool_call",
                            "name", call.name(),
                            "write", isWrite,
                            "args", call.arguments()));

                    long t0 = System.currentTimeMillis();
                    String output = tools.execute(call.name(), call.arguments());
                    int latency = (int) (System.currentTimeMillis() - t0);

                    // 幂等与可观测：写操作单独落表
                    trace.recordToolCall(traceId, call.name(), call.arguments(), latency, isWrite);

                    // 鉴权失败短路：老项目返回 401（token 过期）时不再进 LLM 轮次组织道歉文，
                    // 而是推确定性 auth_expired 事件（前端据此引导重新登录）+ 固定文案收口。
                    // 注意：这里在内层工具循环里，必须用标志位退出「外层」ReAct 循环——
                    // 直接 break 只跳出内层，下一轮 LLM 会带着「有 tool_calls 无 tool 结果」
                    // 的断裂消息序列被网关以 400 拒绝（首版实测踩中）。
                    if (output.contains("\"authFailed\":true") || output.contains("401")) {
                        onToolEvent.accept(Map.of("type", "auth_expired"));
                        finalAnswer = "🔑 登录已过期，请重新登录后再继续，我在这等你回来。";
                        authExpired = true;
                        break;
                    }

                    // 确认流：prepare_order 拟单完成后，向前端推送结构化确认事件（渲染确认/取消按钮）
                    if ("prepare_order".equals(call.name())) {
                        try {
                            JsonNode out = mapper.readTree(output);
                            if (out.hasNonNull("confirmToken")) {
                                onToolEvent.accept(Map.of(
                                        "type", "confirm_ready",
                                        "confirmToken", out.path("confirmToken").asText(),
                                        "summary", draftSummary(out.path("draft"))));
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    // 画像：确认下单成功后失效缓存，下一轮按新订单重建
                    if (output.contains("ORDER_CREATED") && userId != null) {
                        userProfile.invalidate(userId);
                    }

                    messages.add(Message.tool(call.id(), call.name(), truncate(output, 4000)));
                }

                // 鉴权失败：从内层工具循环带标志退出外层 ReAct 循环
                if (authExpired) break;
            }

            if (finalAnswer == null) {
                finalAnswer = "抱歉，我没能完成这个请求，请换个说法试试。";
            }

            // ---------- 4. 流式输出最终答案 ----------
            // 说明：ReAct 循环里已用非流式拿完整结果；最终答案这里逐块吐出给前端。
            // 若要端到端真流式，可在最后一轮改用 llm.chatStream()。
            for (String piece : splitForStream(finalAnswer)) {
                onDelta.accept(piece);
                sleep(12);   // 轻微节流，让前端呈现打字机效果
            }

            // ---------- 5. 落库与记忆 ----------
            int latencyMs = (int) (System.currentTimeMillis() - start);
            memory.append(memoryScope, userInput, finalAnswer);
            trace.recordTrace(traceId, sessionId, "agent.run",
                    totalPromptTokens, totalCompletionTokens, latencyMs);

            onDone.accept(Map.of(
                    "traceId", traceId,
                    "sessionId", sessionId,
                    "toolCalls", toolCallCount,
                    "promptTokens", totalPromptTokens,
                    "completionTokens", totalCompletionTokens,
                    "latencyMs", latencyMs,
                    "costYuan", estimateCost(totalPromptTokens, totalCompletionTokens)));

        } catch (Exception e) {
            log.error("[{}] Agent 运行异常", traceId, e);
            onError.accept(e);
        } finally {
            FeignAuthConfig.clear();
        }
    }

    // ==================== 意图路由 ====================

    /**
     * 规则优先的两级路由：命中关键词直接锁定候选工具，避免把 20+ 工具 schema 全塞进 prompt。
     * 未命中任何规则时，退回「只读工具集合」——绝不默认放行写操作。
     */
    private List<String> routeIntent(String input) {
        String q = input.toLowerCase();

        // 顺序即优先级，务必谨慎：
        // 「下单」分支含单字「买」，若排在「购物车」之前，会把「买X加入购物车」
        // 这类句子抢走，导致候选工具里没有 add_to_cart，模型只能答「没有加购功能」。
        // 因此把更具体的「购物车/加购」放在前面；「买」只作为下单兜底（见分支注释）。
        if (containsAny(q, "购物车", "加购", "cart"))
            return List.of("view_cart", "add_to_cart", "remove_from_cart", "search_tea", "get_tea_detail");
        if (containsAny(q, "下单", "购买", "结算", "order"))
            return List.of("search_tea", "get_tea_detail", "list_addresses", "add_address", "view_cart",
                    "prepare_order", "confirm_order", "cancel_order");
        // 单字「买」放最后：它太宽泛（「买2份」/「想买」都会命中），
        // 只在没有更具体意图时才走下单流程。
        if (containsAny(q, "买"))
            return List.of("search_tea", "get_tea_detail", "list_addresses", "add_address", "view_cart",
                    "prepare_order", "confirm_order", "cancel_order");
        if (containsAny(q, "订单", "history", "买的"))
            return List.of("list_orders");
        if (containsAny(q, "优惠券", "券", "兑换", "coupon"))
            return List.of("list_coupons", "redeem_coupon");
        if (containsAny(q, "地址", "收货"))
            return List.of("list_addresses", "add_address");
        if (containsAny(q, "分类", "有哪些种类", "品类", "category"))
            return List.of("list_categories", "list_themes");
        if (containsAny(q, "店铺", "茶铺", "shop"))
            return List.of("list_shop_products");
        if (containsAny(q, "主题", "系列", "theme"))
            return List.of("list_themes");
        // 用户确认下单。注意：不能用「可以」「好的」这类泛词——
        // 「可以推荐几款绿茶吗」会被误判为确认，从而放行 prepare/confirm 写操作工具
        // （实测 prompt 从 1737 涨到 3037 tokens，且模型可能直接拟单）。
        // 「确认」「同意」保留：它们是用户批准订单草稿的唯一途径，且语境相对明确。
        if (containsAny(q, "确认", "同意", "就这个", "就它了", "下单吧", "确认购买"))
            return List.of("confirm_order", "cancel_order", "prepare_order", "search_tea", "get_tea_detail");

        // 兜底：只读工具
        return List.of("search_tea", "get_tea_detail", "list_themes", "list_categories",
                "view_cart", "list_orders");
    }

    /** 判断是否需要走 RAG：问「是什么/怎么/为什么/区别」这类知识题才检索。 */
    private boolean needsKnowledge(String input) {
        return containsAny(input, "怎么", "如何", "为什么", "区别", "是什么", "功效", "保存", "冲泡", "工艺");
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    // ==================== 辅助 ====================

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    /** 把拟单压成一行摘要，供前端确认按钮展示。 */
    private String draftSummary(JsonNode draft) {
        return draft.path("productEmoji").asText("") + " "
                + draft.path("productName").asText("") + "（" + draft.path("specName").asText() + "）"
                + " ×" + draft.path("quantity").asInt()
                + " = ¥" + draft.path("subtotal").asDouble()
                + "，送 " + draft.path("addressSummary").asText("");
    }

    /** 把完整答案切成小片做流式呈现。 */
    private List<String> splitForStream(String text) {
        List<String> out = new ArrayList<>();
        int step = 8;
        for (int i = 0; i < text.length(); i += step) {
            out.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return out;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * 成本估算（按 DeepSeek 公开价折算，实际以账单为准）。
     * 简历上写成本数字时，必须用这张表 + agent_trace 表里的真实 token 数聚合。
     */
    private double estimateCost(int promptTokens, int completionTokens) {
        double in = promptTokens / 1_000_000.0 * 1.0;    // 输入 1 元/百万 token
        double out = completionTokens / 1_000_000.0 * 2.0; // 输出 2 元/百万 token
        return Math.round((in + out) * 1_000_000) / 1_000_000.0;
    }
}
