package com.guchen.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final com.guchen.agent.tool.TradeToolService draft;

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

    /**
     * 裸确认/裸取消：用户只说「确认」「确认吧」「取消」这类词，没带令牌。
     * 这类输入不能交给 LLM——它手上没有令牌，只会「重新研究一遍」，
     * 而重新研究用的搜索数据不完整，反过来会否定用户已经选对的东西
     * （实测：用户选「武功山云雾·品鉴装50g」（真实存在，spec_id=9，¥98），
     *  模型复核后回复「并无品鉴装50g，我无法按此下单」，连卡三次）。
     * 这里做确定性兜底：有草稿就引导带令牌确认，没草稿就直说没有待确认订单。
     */
    private static final java.util.regex.Pattern BARE_CONFIRM_PATTERN =
            java.util.regex.Pattern.compile("^\s*(确认|确定|同意|就这个|就它了|下单吧|确认购买|好的?|可以|嗯)\s*[吧呢啊。！!，,~～]*\s*$");

    private static final java.util.regex.Pattern BARE_CANCEL_PATTERN =
            java.util.regex.Pattern.compile("^\s*(取消|不要了|算了|不买了|撤销)\s*[吧呢啊。！!，,~～]*\s*$");

    /**
     * 地址选择协议：前端地址卡片点选后发出「用地址 26」/「用地址26」。
     * 解析出 addressId 后写成显式指令交给 LLM（而不是让 LLM 自己从历史里猜 id）。
     */
    private static final java.util.regex.Pattern PICK_ADDRESS_PATTERN =
            java.util.regex.Pattern.compile("^\\s*用地址\\s*[:：]?\\s*(\\d{1,12})\\s*$");

    /**
     * 推荐卡片选购协议：前端点「选这个」后发出「选购 <productId> <specId> <qty>」。
     * 这是**结构化指令**——商品、规格、数量都由用户在卡片上点定，
     * 后端直接用它拟单，不必再让模型解析「用户想要哪个规格」，
     * 也就省掉了原来那轮「请问要什么规格」的追问。
     */
    private static final java.util.regex.Pattern PICK_SPEC_PATTERN =
            java.util.regex.Pattern.compile("^\\s*选购\\s+(\\d{1,12})\\s+(\\d{1,12})\\s+(\\d{1,3})\\s*$");

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
            7.5 【收货地址：默认直接用，不要反复询问】
               ⚠️ 除非用户明说「换个地址」「改地址」，否则**不要**为了确认地址而中断下单流程，
               也不要调用 request_address_pick 让用户选——直接调用 prepare_order（省略 addressId
               即自动使用默认地址）。系统会在确认弹窗上展示收货地址，并把「点击修改」交给用户按需触发。
               用户从地址卡片/弹层选好后会发「用地址 <id>」，届时作为 addressId 传给 prepare_order。
            7.6 【只确认一次，禁止反复追问】
               拟单成功后，**一次性**把关键信息说清楚即可（商品/规格/数量/金额/收货地址），
               然后**立刻停止**，等用户点确认弹窗上的按钮。
               ⚠️ 严禁出现「请确认规格与数量」→「请确认地址」→「请先去卡片点选」这类**多轮重复追问**：
               - 用户没说规格时按默认规格直接拟单，不要反问；
               - 用户没说数量时按 1 份直接拟单，不要反问；
               - 地址一律用默认地址，不要反问。
               只有两种情况才需要追问：① 商品没搜到或有多款需要用户挑；② 用户主动要求修改。
               拟单后系统会自动弹出确认窗口，你**不需要**在文字里再让用户"回复确认"。
            8. 【规格判断】判断某商品「有没有某个规格」时，**必须以接口返回的规格清单为准**：
               search_tea 返回的 specs 字段、或 get_tea_detail 返回的 productSpecs。
               ⚠️ search_tea 顶层那个 `spec`/`price` 只是**默认规格的展示副本**——
               默认是「标准装 100g」绝不代表该商品只有这一个规格，其他装量很可能同样存在。
               **绝对禁止**因为默认规格与用户所说不同，就断言「没有这个规格 / 我无法下单」。
               拿不准时先调 get_tea_detail 看完整规格清单，或向用户确认，而不是替用户下结论。
            9. 【不得推翻用户的正确选择】用户已经明确说出商品与规格、且该规格在接口数据里真实存在时，
               直接按它拟单。禁止自作主张「重新核实」后否定用户的选择；
               只有当规格**确实不在**返回清单里时，才说明可用规格并请用户重选。
            10. 【硬性要求】涉及下单时，**必须实际调用 prepare_order 工具**，绝不允许不调工具、
                仅凭记忆或上下文就用文字罗列「商品/规格/数量/合计/收货地址」并让用户确认。
                ⚠️ 你没有能力自行生成确认令牌——令牌只能由 prepare_order 返回。
                任何由你自己写出来的令牌都是无效的，会造成用户下单失败。
                正确顺序永远是：search_tea（拿到真实 productId）→ prepare_order（拿到令牌）→ 复述并等确认。
            11. 【加入购物车：直接执行，不要确认】
                ⚠️ 加购是**低风险操作**，不需要用户二次确认，也**不要**说「回复确认我就提交」这类话——
                用户说「加入购物车」就是要加，直接调用 add_to_cart 完成即可。
                同样**必须真调 add_to_cart 工具**，绝不能只用文字说「已为您加入购物车」——
                不调工具时购物车实际是空的，这是欺骗用户。
                规格/数量缺省时按默认规格、1 份直接加，不要反问。
                系统会在加购成功后自动展示购物车内容，你不需要复述购物车清单。
            12. 【推荐茶叶：必须生成选购卡片】
                ⚠️ 当你向用户**推荐**茶叶（「推荐几款绿茶」「送长辈什么茶」等）时，
                必须先 search_tea 拿到真实商品，再调用 **recommend_tea** 把推荐商品登记成卡片
                （productIds 取自搜索结果）。
                调用后**只用一两句话说明推荐理由**即可——
                **不要再逐条罗列规格和价格**（卡片上已经展示），也不要问「您要哪个规格」，
                用户会直接在卡片上点选并选择规格。重复罗列会让用户觉得啰嗦。
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
                if (!cancel) draft.clearPending(sessionId);
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

            // ---------- 裸确认/裸取消兜底（不经 LLM） ----------
            // 用户说了确认类的词但没带令牌。绝不能放给 LLM：它没有令牌，
            // 只能去"重新研究"，而研究用的数据不完整时会反过来否定用户已选对的东西。
            String trimmed = userInput == null ? "" : userInput.trim();
            boolean bareConfirm = BARE_CONFIRM_PATTERN.matcher(trimmed).matches();
            boolean bareCancel = BARE_CANCEL_PATTERN.matcher(trimmed).matches();
            if (bareConfirm || bareCancel) {
                String pending = draft.pendingToken(sessionId);
                String reply;
                if (pending == null || pending.isBlank()) {
                    // 没有待确认草稿时，不能一律说「没有订单草稿」——
                    // 用户可能是在确认"加购"（加购本就不需要确认），
                    // 一刀切的话会答非所问（实测：用户确认加购，收到"当前没有待确认的订单草稿"）。
                    // 这里顺带查一次购物车：有货就直接告诉他购物车状态，没有才引导去下单。
                    String cartHint = cartSummaryHint();
                    if (bareCancel) {
                        reply = "当前没有待确认的订单，不需要取消。想买什么直接告诉我就行～";
                    } else if (cartHint != null) {
                        reply = "您的购物车当前有：" + cartHint + "\n\n"
                                + "加入购物车不需要确认，已经生效了。要下单的话告诉我，我帮您拟单～";
                    } else {
                        reply = "当前没有待确认的订单草稿，购物车也是空的。\n"
                                + "告诉我您想买哪款茶（商品 + 规格 + 数量）就行，"
                                + "我会直接加进购物车；要下单再说一声，我帮您拟单。";
                    }
                } else if (bareCancel) {
                    tools.execute("cancel_order", "{\"confirmToken\":\"" + pending + "\"}");
                    draft.clearPending(sessionId);
                    reply = "已取消刚才那笔拟单，令牌已作废。需要重新下单随时说～";
                } else {
                    // 有待确认草稿但用户没带令牌 —— 把令牌明确交到他手上，一步到位
                    reply = "您有一笔待确认的订单草稿，但确认需要带上令牌。\n"
                            + "请回复：**确认下单，令牌 " + pending + "**\n"
                            + "（令牌 5 分钟内有效；要放弃就说「取消」）";
                }
                onToolEvent.accept(Map.of("type", "confirm_guide",
                        "hasPending", pending != null && !pending.isBlank(), "bare", trimmed));
                for (String piece : splitForStream(reply)) {
                    onDelta.accept(piece);
                    sleep(12);
                }
                // 只有「取消」是真实业务动作，值得记住；纯引导话术不落记忆——
                // 尤其其中含一次性令牌，写进长期记忆会让模型在后续轮次复述一个已失效的令牌。
                if (bareCancel && pending != null && !pending.isBlank()) {
                    memory.append(memoryScope, userInput, reply);
                }
                onDone.accept(Map.of("traceId", traceId, "sessionId", sessionId,
                        "toolCalls", 0, "promptTokens", 0, "completionTokens", 0,
                        "latencyMs", (int) (System.currentTimeMillis() - start), "costYuan", 0.0));
                return;
            }

            // ---------- 0.5 推荐卡片选购直通（不经 LLM） ----------
            // 前端点「选这个」→ 发「选购 <productId> <specId> <qty>」。
            // 商品/规格/数量都是用户在卡片上点定的结构化数据，直接拟单即可——
            // 这正是这个卡片存在的意义：省掉「请问要什么规格」那一轮追问。
            // 交给 LLM 反而有风险（它可能重新研究、甚至换掉用户选的规格）。
            java.util.regex.Matcher ps = PICK_SPEC_PATTERN.matcher(trimmed);
            if (ps.matches()) {
                long pid = Long.parseLong(ps.group(1));
                long sid = Long.parseLong(ps.group(2));
                int qty = Integer.parseInt(ps.group(3));
                // 作废旧草稿：避免两个令牌同时有效
                String stale = draft.pendingToken(sessionId);
                if (stale != null && !stale.isBlank()) {
                    tools.execute("cancel_order", "{\"confirmToken\":\"" + stale + "\"}");
                }
                String args = "{\"productId\":" + pid + ",\"specId\":" + sid + ",\"quantity\":" + qty + "}";
                log.info("[{}] 推荐卡片选购直通：productId={} specId={} qty={}", traceId, pid, sid, qty);
                onToolEvent.accept(Map.of("type", "tool_call", "name", "prepare_order",
                        "write", false, "args", args));
                String out = tools.execute("prepare_order", args);
                trace.recordToolCall(traceId, "prepare_order", args, 0, false);
                try {
                    JsonNode node = mapper.readTree(out);
                    if (node.hasNonNull("confirmToken") && !node.hasNonNull("error")) {
                        String tk = node.path("confirmToken").asText();
                        JsonNode d = node.path("draft");
                        draft.markPending(sessionId, tk);
                        ObjectNode payload = mapper.createObjectNode();
                        payload.put("type", "confirm_ready");
                        payload.put("confirmToken", tk);
                        payload.put("confirmPhrase", "确认下单，令牌 " + tk);
                        payload.put("summary", draftSummary(d));
                        payload.put("productName", d.path("productName").asText());
                        payload.put("productEmoji", d.path("productEmoji").asText());
                        payload.put("specName", d.path("specName").asText());
                        payload.put("quantity", d.path("quantity").asInt());
                        payload.put("unitPrice", d.path("unitPrice").asDouble());
                        payload.put("subtotal", d.path("subtotal").asDouble());
                        payload.put("shopName", d.path("shopName").asText());
                        payload.put("addressId", d.path("addressId").asLong());
                        payload.put("addressSummary", d.path("addressSummary").asText());
                        onToolEvent.accept(mapper.convertValue(payload,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    } else {
                        // 拟单失败（商品/规格/库存问题）：把工具给的原因如实转达
                        String msg = node.path("error").asText("下单信息有误，请重新选择");
                        for (String piece : splitForStream("⚠️ " + msg)) {
                            onDelta.accept(piece);
                            sleep(12);
                        }
                        onDone.accept(Map.of("traceId", traceId, "sessionId", sessionId,
                                "toolCalls", 1, "promptTokens", 0, "completionTokens", 0,
                                "latencyMs", (int) (System.currentTimeMillis() - start), "costYuan", 0.0));
                        return;
                    }
                } catch (Exception e) {
                    log.warn("[{}] 选购直通拟单解析失败: {}", traceId, e.getMessage());
                }
                String reply = "好的，已为您拟好订单。请在上方确认卡片核对商品、规格、金额与收货地址，"
                        + "没问题就点「确认购买」；要改地址可以点「修改」。";
                for (String piece : splitForStream(reply)) {
                    onDelta.accept(piece);
                    sleep(12);
                }
                memory.append(memoryScope, userInput, reply);
                onDone.accept(Map.of("traceId", traceId, "sessionId", sessionId,
                        "toolCalls", 1, "promptTokens", 0, "completionTokens", 0,
                        "latencyMs", (int) (System.currentTimeMillis() - start), "costYuan", 0.0));
                return;
            }

            // ---------- 1. 意图路由（确定性） ----------
            // 地址卡片点选：「用地址 28」→ 改写成显式拟单指令。
            // 不能原样丢给 LLM：实测模型会把「用地址 28」当成一次地址查询，
            // 只调 list_addresses 就收尾，不再继续拟单（拟单链路断在这里）。
            // 这里把它翻译成「用 addressId=28 拟单」的硬指令，模型只需照做。
            String effectiveInput = userInput;
            java.util.regex.Matcher pa = PICK_ADDRESS_PATTERN.matcher(trimmed);
            String selectedAddressId = null;
            if (pa.matches()) {
                String aid = pa.group(1);
                selectedAddressId = aid;
                // 换地址会生成新草稿，先作废旧的：避免两个令牌同时有效导致用户点到过期的那个
                String staleToken = draft.pendingToken(sessionId);
                if (staleToken != null && !staleToken.isBlank()) {
                    tools.execute("cancel_order", "{\"confirmToken\":\"" + staleToken + "\"}");
                }
                effectiveInput = "【系统指令】用户已从地址卡片选定收货地址，addressId=" + aid + "。"
                        + "请直接用该 addressId 调用 prepare_order 完成拟单（productId/specId/quantity "
                        + "沿用上一轮已确认的商品与规格），然后复述拟单内容等待用户确认。"
                        + "不要重新查询地址，也不要把「用地址」当成新的购物需求。";
                log.info("[{}] 地址卡片选定 addressId={}，改写为拟单指令", traceId, aid);
            }

            List<String> candidateTools = routeIntent(userInput);
            if (pa.matches()) {
                // 点选地址后必然要拟单，路由必须放行 prepare_order，否则模型无工具可用
                candidateTools = List.of("prepare_order", "confirm_order", "cancel_order",
                        "search_tea", "get_tea_detail", "list_addresses");
            }
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
            messages.add(Message.user(effectiveInput));

            // ---------- 3. ReAct 循环（受控自主） ----------
            String finalAnswer = null;
            boolean authExpired = false;
            boolean blockedDraft = false;
            boolean recommendPushed = false;              // 本轮是否已推过推荐卡片
            List<String> lastSearchedProductIds = new ArrayList<>();  // 最近一次 search_tea 的商品 id
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

                    // ===== 收货地址：默认直接用，不阻断流程（对齐千问交互） =====
                    // 设计变更（用户实测反馈「重复询问太多次」）：
                    //   旧实现强制用户先点地址卡片才能拟单，导致「问规格→问地址→去点卡片→再确认」
                    //   三四轮来回。而千问的做法是**默认地址直接带上**，只在回复里说一句
                    //   「当前地址是 XXX（点击修改）」，用户觉得不对才去改。
                    //   因此这里不再拦截 prepare_order；改为在拟单时顺带把默认地址信息推给前端，
                    //   让确认卡片上一并展示，用户不满意可点「修改」换地址后重新拟单。
                    String output;
                    if ("prepare_order".equals(call.name())) {
                        // ===== 规格未确认时先弹选购卡片，不替用户决定（服务端强制） =====
                        // 实测缺陷：用户说「推荐长辈喜欢的」→ 模型没调 recommend_tea 只报了茶名；
                        // 用户接着说「就大红袍吧」→ 模型直接 prepare_order，**自己取了默认规格**下单。
                        // 用户从没选过规格，却被替做了决定。
                        // prompt 约束不可靠（模型可以无视），这里做硬校验：
                        // 本轮用户没明确指定规格、也不是从卡片点选过来的，就先推规格卡片让用户选。
                        boolean userPickedSpec = ps.matches()          // 来自推荐卡片「选这个」
                                || specMentionedByUser(trimmed);        // 用户话里明确说了规格/装量
                        if (!userPickedSpec) {
                            long pidForPick = mapper.readTree(call.arguments()).path("productId").asLong(0);
                            if (pidForPick > 0) {
                                String cardsJson = tools.execute("recommend_tea",
                                        "{\"productIds\":[" + pidForPick + "]}");
                                try {
                                    JsonNode cards = mapper.readTree(cardsJson).path("cards");
                                    if (cards.isArray() && !cards.isEmpty()) {
                                        onToolEvent.accept(Map.of("type", "recommend_cards", "cards", cards));
                                        log.info("[{}] 用户未指定规格，已拦截拟单并推规格卡片 productId={}",
                                                traceId, pidForPick);
                                        // 不执行 prepare_order，改由用户点选规格后再走「选购」直通
                                        messages.add(Message.tool(call.id(), call.name(),
                                                "{\"needSpecPick\":true,\"note\":\"已向用户展示规格卡片，"
                                                + "请等待用户点选后再拟单，不要自行选择规格\"}"));
                                        toolCallCount--;
                                        continue;
                                    }
                                } catch (Exception e) {
                                    log.warn("[{}] 规格卡片推送失败，放行拟单: {}", traceId, e.getMessage());
                                }
                            }
                        }
                        output = tools.execute(call.name(), call.arguments());
                        try {
                            JsonNode out = mapper.readTree(output);
                            if (out.hasNonNull("confirmToken") && !out.hasNonNull("error")) {
                                JsonNode d = out.path("draft");
                                onToolEvent.accept(Map.of(
                                        "type", "address_line",
                                        "addressId", d.path("addressId").asLong(),
                                        "addressSummary", d.path("addressSummary").asText()));
                            }
                        } catch (Exception ignored) {
                        }
                    } else {
                        output = tools.execute(call.name(), call.arguments());
                    }
                    int latency = (int) (System.currentTimeMillis() - t0);

                    // 幂等与可观测：写操作单独落表
                    trace.recordToolCall(traceId, call.name(), call.arguments(), latency, isWrite);

                    // 鉴权失败短路：老项目返回 401（token 过期）时不再进 LLM 轮次组织道歉文，
                    // 而是推确定性 auth_expired 事件（前端据此引导重新登录）+ 固定文案收口。
                    // 注意：这里在内层工具循环里，必须用标志位退出「外层」ReAct 循环——
                    // 直接 break 只跳出内层，下一轮 LLM 会带着「有 tool_calls 无 tool 结果」
                    // 的断裂消息序列被网关以 400 拒绝（首版实测踩中）。
                    //
                    // ⚠️ 判定必须走结构化字段，不能裸子串匹配 "401"：
                    // 老项目地址数据里 districtCode="340102" 就**含子串 401**，
                    // 原先 output.contains("401") 会把正常的地址查询误判为鉴权失效，
                    // 导致明明登录有效却回「登录已过期」，且该错误被写进跨会话记忆反复复发。
                    if (isAuthFailure(output)) {
                        onToolEvent.accept(Map.of("type", "auth_expired"));
                        finalAnswer = "🔑 登录已过期，请重新登录后再继续，我在这等你回来。";
                        authExpired = true;
                        break;
                    }

                    // 确认流：prepare_order 拟单完成后，向前端推送结构化确认事件（渲染确认/取消按钮）
                    if ("prepare_order".equals(call.name())) {
                        try {
                            JsonNode out = mapper.readTree(output);
                            if (out.hasNonNull("confirmToken") && !out.hasNonNull("error")) {
                                String tk = out.path("confirmToken").asText();
                                JsonNode d = out.path("draft");
                                // 记录本会话有待确认草稿，供「裸确认」兜底识别
                                draft.markPending(sessionId, tk);
                                // 一次推全：弹窗需要商品/规格/数量/单价/合计/**收货地址**，
                                // 全部塞进结构化字段，前端才能只弹一次、用户只确认一次。
                                ObjectNode payload = mapper.createObjectNode();
                                payload.put("type", "confirm_ready");
                                payload.put("confirmToken", tk);
                                payload.put("confirmPhrase", "确认下单，令牌 " + tk);
                                payload.put("summary", draftSummary(d));
                                payload.put("productName", d.path("productName").asText());
                                payload.put("productEmoji", d.path("productEmoji").asText());
                                payload.put("specName", d.path("specName").asText());
                                payload.put("quantity", d.path("quantity").asInt());
                                payload.put("unitPrice", d.path("unitPrice").asDouble());
                                payload.put("subtotal", d.path("subtotal").asDouble());
                                payload.put("shopName", d.path("shopName").asText());
                                payload.put("addressId", d.path("addressId").asLong());
                                payload.put("addressSummary", d.path("addressSummary").asText());
                                onToolEvent.accept(
                                        mapper.convertValue(payload, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                            } else if (out.hasNonNull("error")) {
                                log.warn("[{}] prepare_order 被拒: {}", traceId, out.path("error").asText());
                            }
                        } catch (Exception e) {
                            // 不能静默吞：解析失败意味着前端收不到确认按钮，用户会卡住
                            log.warn("[{}] prepare_order 结果解析失败: {} output={}", traceId, e.getMessage(), output);
                        }
                    }

                    // 地址确认：模型调 request_address_pick 时，把地址列表推给前端渲染成可选卡片
                    // （高德/千问风格：单选项 + 常用/公司标签 + 姓名电话 + 底部「新增收货地址」）。
                    // 这样用户在**点选**而不是打字，既降低出错也符合移动端习惯。
                    if ("request_address_pick".equals(call.name())) {
                        try {
                            JsonNode out = mapper.readTree(output);
                            JsonNode arr = out.path("addressList");
                            if (arr.isArray() && !arr.isEmpty()) {
                                ArrayNode slim = mapper.createArrayNode();
                                for (JsonNode a : arr) {
                                    ObjectNode one = mapper.createObjectNode();
                                    one.put("addressId", a.path("id").asLong());
                                    one.put("name", a.path("name").asText());
                                    one.put("phone", a.path("phone").asText());
                                    one.put("tag", a.path("tag").asText());
                                    one.put("fullAddress", a.path("fullAddress").asText());
                                    one.put("isDefault", a.path("isDefault").asInt());
                                    slim.add(one);
                                }
                                onToolEvent.accept(Map.of("type", "confirm_address", "addresses", slim));
                            }
                        } catch (Exception e) {
                            log.warn("[{}] 地址列表解析失败: {} output={}", traceId, e.getMessage(), output);
                        }
                    }

                    // 推荐选购卡片：模型调 recommend_tea 时，把卡片推给前端渲染成可点选列表。
                    // 用户点「选这个」→ 选规格数量 → 直接拟单，不必再让模型追问规格。
                    if ("recommend_tea".equals(call.name())) {
                        try {
                            JsonNode out = mapper.readTree(output);
                            JsonNode cards = out.path("cards");
                            if (cards.isArray() && !cards.isEmpty()) {
                                onToolEvent.accept(Map.of("type", "recommend_cards", "cards", cards));
                                recommendPushed = true;
                                log.debug("[{}] 已推送推荐卡片 {} 张", traceId, cards.size());
                            }
                        } catch (Exception e) {
                            log.warn("[{}] 推荐卡片解析失败: {}", traceId, e.getMessage());
                        }
                    }

                    // 记录 search_tea 命中的商品 id，供「推荐意图兜底补推卡片」使用
                    if ("search_tea".equals(call.name())) {
                        try {
                            JsonNode list = mapper.readTree(output).path("ShopSpecList");
                            if (list.isArray()) {
                                lastSearchedProductIds.clear();
                                for (JsonNode it : list) {
                                    long id = it.path("id").asLong(0);
                                    if (id > 0 && lastSearchedProductIds.size() < 5) {
                                        lastSearchedProductIds.add(String.valueOf(id));
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    // 购物车卡片：加购/删除成功后，主动查一次购物车并推给前端渲染成卡片。
                    // 为什么要服务端主动推（真实缺陷修复）：
                    //   实测模型调了 add_to_cart 后只用文字说「已为您加入购物车」，
                    //   用户看不到购物车里到底有什么，也不知道成没成；
                    //   更糟的是模型有时**根本没调工具**，纯文字说"已加入"（日志 tool_calls=0），
                    //   购物车实际是空的。这里在工具真正执行成功后由服务端补一次购物车快照，
                    //   让「有没有真的加进去」变成可见事实，而不是靠模型自述。
                    if ("add_to_cart".equals(call.name()) || "remove_from_cart".equals(call.name())) {
                        boolean ok = !output.contains("\"error\"");
                        if (ok) {
                            try {
                                String cartJson = tools.execute("view_cart", "{}");
                                JsonNode cart = mapper.readTree(cartJson);
                                JsonNode list = cart.path("VCartDetailEnhancedList");
                                if (list.isArray()) {
                                    // 老项目视图 v_cart_detail_enhanced 直接 JOIN theme_product（多对多），
                                    // 一条 cart_item 会被放大成 N 行（实测 cartItemId=53 出现 2 次）。
                                    // 必须按 cartItemId 去重，否则前端会显示重复商品、合计翻倍。
                                    java.util.LinkedHashMap<Long, ObjectNode> dedup = new java.util.LinkedHashMap<>();
                                    double total = 0;
                                    for (JsonNode it : list) {
                                        long cid = it.path("cartItemId").asLong();
                                        if (cid <= 0 || dedup.containsKey(cid)) continue;
                                        ObjectNode one = mapper.createObjectNode();
                                        one.put("cartItemId", cid);
                                        one.put("productId", it.path("productId").asLong());
                                        one.put("productName", it.path("productName").asText());
                                        one.put("productEmoji", it.path("productEmoji").asText());
                                        one.put("specId", it.path("specId").asLong());
                                        one.put("specName", it.path("specName").asText());
                                        one.put("quantity", it.path("quantity").asInt());
                                        one.put("price", it.path("specPrice").asDouble());
                                        double sub = it.path("subtotal").asDouble();
                                        one.put("subtotal", sub);
                                        one.put("shopName", it.path("shopName").asText());
                                        dedup.put(cid, one);
                                        total += sub;
                                    }
                                    ArrayNode items = mapper.createArrayNode();
                                    dedup.values().forEach(items::add);
                                    ObjectNode payload = mapper.createObjectNode();
                                    payload.put("type", "cart_snapshot");
                                    payload.put("count", dedup.size());
                                    payload.put("totalPrice", Math.round(total * 100) / 100.0);
                                    payload.put("justAdded", "add_to_cart".equals(call.name()));
                                    payload.set("items", items);
                                    onToolEvent.accept(mapper.convertValue(payload,
                                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                                    log.debug("[{}] 已推送购物车快照：{} 项，合计 {}", traceId, dedup.size(), total);
                                }
                            } catch (Exception e) {
                                log.warn("[{}] 购物车快照推送失败: {}", traceId, e.getMessage());
                            }
                        } else if ("add_to_cart".equals(call.name()) && output.contains("500")) {
                            // 老项目缺陷 #5：cart_item 唯一键 uk_cart_product=(cart_id,product_id) **不含 spec_id**，
                            // 同一商品换规格再加购会撞唯一键直接 500（老项目本体不修，见交接文档 §6.2）。
                            // 这里给用户可理解的说明，而不是把 500 原样抛给他；
                            // 同时把当前购物车推过去，让他看到这件商品已经在车里（只是规格不同）。
                            log.info("[{}] 加购撞唯一键（缺陷#5，同商品换规格），转为友好提示", traceId);
                            String hint = cartSummaryHint();
                            String reply = "这件茶已经在您的购物车了，只是**规格不同**——"
                                    + "购物车的同一个商品只能保留一个规格。\n"
                                    + (hint != null ? "当前购物车：" + hint + "\n\n" : "\n")
                                    + "如果要换成别的规格，可以先去购物车删掉原来那条，再让我加新的；"
                                    + "或者直接告诉我「买这个规格」，我帮您下单（下单不受此限制）。";
                            onToolEvent.accept(Map.of("type", "cart_conflict"));
                            for (String piece : splitForStream(reply)) {
                                onDelta.accept(piece);
                                sleep(12);
                            }
                            memory.append(memoryScope, userInput, reply);
                            onDone.accept(Map.of("traceId", traceId, "sessionId", sessionId,
                                    "toolCalls", toolCallCount, "promptTokens", totalPromptTokens,
                                    "completionTokens", totalCompletionTokens,
                                    "latencyMs", (int) (System.currentTimeMillis() - start),
                                    "costYuan", estimateCost(totalPromptTokens, totalCompletionTokens)));
                            return;
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

            // ---------- 4.4 推荐场景兜底：模型没推卡片时补推 ----------
            // 实测缺陷：用户说「推荐长辈喜欢的」，模型 tool_calls=0 纯文字回复，
            // 没调 recommend_tea → 前端收不到卡片 → 用户只能靠文字里提到的茶名回一句
            // 「就大红袍吧」，然后又因为没有规格卡片而被模型自作主张拟单。
            // prompt 规则不可靠（模型可以无视），这里做服务端兜底。
            if (!recommendPushed && isRecommendIntent(trimmed)) {
                try {
                    // 情形 A：本轮搜过商品 → 直接拿搜索结果补推卡片（不再花 LLM 调用）
                    if (!lastSearchedProductIds.isEmpty()) {
                        String cardsJson = tools.execute("recommend_tea",
                                "{\"productIds\":[" + String.join(",", lastSearchedProductIds) + "]}");
                        JsonNode cards = mapper.readTree(cardsJson).path("cards");
                        if (cards.isArray() && !cards.isEmpty()) {
                            onToolEvent.accept(Map.of("type", "recommend_cards", "cards", cards));
                            recommendPushed = true;
                            log.info("[{}] 推荐意图未推卡片，已用搜索结果补推 {} 张", traceId, cards.size());
                        }
                    }
                    // 情形 B：本轮连搜都没搜（模型凭上下文直接作答）→ 追一轮强制它调 recommend_tea。
                    // 此时模型刚在文字里报过茶名，带着上下文再问一次，它知道该推荐什么。
                    if (!recommendPushed) {
                        List<Message> nudge = new ArrayList<>(messages);
                        nudge.add(Message.assistant(finalAnswer));
                        nudge.add(Message.system("【系统指令】你刚才的推荐没有生成选购卡片，用户无法点选。"
                                + "请先用 search_tea 确认商品，再调用 recommend_tea 把推荐的商品登记成卡片。"
                                + "本轮只做这两件事，不要输出长篇文字。"));
                        ArrayNode nudgeTools = tools.buildToolsSchema(
                                List.of("search_tea", "recommend_tea"));
                        for (int k = 0; k < 2 && !recommendPushed; k++) {
                            ChatResult nr = llm.chatViaStream(nudge, nudgeTools);
                            totalPromptTokens += nr.usage().promptTokens();
                            totalCompletionTokens += nr.usage().completionTokens();
                            if (!nr.hasToolCalls()) break;
                            nudge.add(Message.assistantWithToolCalls(nr.content(),
                                    Message.toolCallsToArray(mapper, nr.toolCalls())));
                            for (ToolCall c : nr.toolCalls()) {
                                String o = tools.execute(c.name(), c.arguments());
                                if ("search_tea".equals(c.name())) {
                                    try {
                                        JsonNode list = mapper.readTree(o).path("ShopSpecList");
                                        if (list.isArray()) {
                                            lastSearchedProductIds.clear();
                                            for (JsonNode it : list) {
                                                long id = it.path("id").asLong(0);
                                                if (id > 0 && lastSearchedProductIds.size() < 5) {
                                                    lastSearchedProductIds.add(String.valueOf(id));
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                                if ("recommend_tea".equals(c.name())) {
                                    try {
                                        JsonNode cards = mapper.readTree(o).path("cards");
                                        if (cards.isArray() && !cards.isEmpty()) {
                                            onToolEvent.accept(Map.of("type", "recommend_cards", "cards", cards));
                                            recommendPushed = true;
                                            log.info("[{}] 追一轮后补推推荐卡片 {} 张", traceId, cards.size());
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                                nudge.add(Message.tool(c.id(), c.name(), truncate(o, 2000)));
                            }
                        }
                        // 兜底仍失败：退化为用搜索到的商品直接推
                        if (!recommendPushed && !lastSearchedProductIds.isEmpty()) {
                            String cardsJson = tools.execute("recommend_tea",
                                    "{\"productIds\":[" + String.join(",", lastSearchedProductIds) + "]}");
                            JsonNode cards = mapper.readTree(cardsJson).path("cards");
                            if (cards.isArray() && !cards.isEmpty()) {
                                onToolEvent.accept(Map.of("type", "recommend_cards", "cards", cards));
                                recommendPushed = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[{}] 推荐卡片兜底失败: {}", traceId, e.getMessage());
                }
            }

            // ---------- 4.5 拦截「文本拟单」（未经工具的口头草稿） ----------
            // 实测：模型有时**不调用 prepare_order**，而是用自然语言罗列订单信息、甚至编一个令牌，
            // 让用户以为可以直接确认。这会导致：
            //   ① 前端收不到 confirm_ready，渲染不出确认按钮；
            //   ② Redis 里没有真草稿，用户回复确认时无处可确认，模型只好「重新研究」并可能否定用户；
            //   ③ 最坏情况是编造令牌，用户以为下单成功。
            // 这里做确定性纠正：只要话术像拟单、但本轮没有任何真实的 prepare_order 成功记录，就换成引导。
            if (draft.pendingToken(sessionId) == null && looksLikeDraftClaim(finalAnswer)) {
                log.warn("[{}] 检测到模型未调用 prepare_order 却输出拟单话术，已拦截并改为引导。原文={}",
                        traceId, finalAnswer);
                finalAnswer = "抱歉，刚才的订单信息未经系统核对，不能直接确认。\n"
                        + "请告诉我您要买的**商品 + 规格 + 数量**（例如「武功山云雾 品鉴装50g 买1份」），"
                        + "我会生成正式的订单草稿和确认令牌，那时再点确认即可。";
                onToolEvent.accept(Map.of("type", "draft_blocked"));
                blockedDraft = true;
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
            // ⚠️ 只把「正常业务回答」写入长期记忆。
            // 记忆域对登录用户是 u{userId}（跨会话共享），一旦把鉴权失败、拦截提示这类
            // 过程性/错误性内容写进去，后续每一轮 LLM 都会读到，并**模仿自己的错误回答**
            // （实测：几轮测试失败后，"登录已过期"被反复复用，即使 token 完全有效）。
            // 这属于典型的「错误信息污染长期记忆 → 正反馈循环」，必须在这里挡住。
            if (authExpired || blockedDraft) {
                log.info("[{}] 本轮为异常/拦截收口，不写入长期记忆（authExpired={}, blockedDraft={}）",
                        traceId, authExpired, blockedDraft);
            } else {
                memory.append(memoryScope, userInput, finalAnswer);
            }
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
        // 自然下单表达：「我要X」「来一份X」「要一份」等。
        // ⚠️ 必须用双字以上组合：单字「要」太泛（「要多少钱」「要多久」都会被误判成下单），
        // 这是 §〇.六 B2「禁用单字/泛词关键词」教训的直接应用。
        if (containsAny(q, "我要", "来一", "来份", "要一份", "要一个", "给我来", "帮我买", "来点", "要买"))
            return List.of("search_tea", "get_tea_detail", "list_addresses", "add_address", "view_cart",
                    "prepare_order", "confirm_order", "cancel_order");
        // 单字「买」放最后：它太宽泛（「买2份」/「想买」都会命中），
        // 只在没有更具体意图时才走下单流程。
        if (containsAny(q, "买"))
            return List.of("search_tea", "get_tea_detail", "list_addresses", "add_address", "view_cart",
                    "prepare_order", "confirm_order", "cancel_order");
        // 换/改地址：用户明确要求修改时才给这个工具（下单分支刻意不含它，
        // 避免模型主动"确认地址"导致反复追问）
        if (containsAny(q, "换地址", "改地址", "修改地址", "换个地址", "换收货", "改收货"))
            return List.of("request_address_pick", "add_address", "list_addresses");
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
        // 含 recommend_tea —— 推荐类问题（「推荐几款绿茶」）常落到这里，
        // 必须给模型推荐卡片工具，否则它只能用纯文字罗列，用户还得自己说要哪个规格。
        return List.of("search_tea", "get_tea_detail", "recommend_tea", "list_themes",
                "list_categories", "view_cart", "list_orders");
    }

    /** 判断是否需要走 RAG：问「是什么/怎么/为什么/区别」这类知识题才检索。 */
    private boolean needsKnowledge(String input) {
        return containsAny(input, "怎么", "如何", "为什么", "区别", "是什么", "功效", "保存", "冲泡", "工艺");
    }

    /**
     * 判断用户是否在请求**推荐**茶叶（而非查询具体商品或下单）。
     * 用于「模型没推卡片时兜底补推」——推荐场景必须有卡片，否则用户只能靠文字里的茶名
     * 回一句「就XX吧」，进而被模型自作主张拟单（实测踩中）。
     */
    private boolean isRecommendIntent(String q) {
        if (q == null || q.isBlank()) return false;
        return containsAny(q, "推荐", "有什么好", "哪些好", "送什么", "送长辈", "送礼",
                "适合", "挑几款", "选几款", "来几款", "什么茶好", "好喝");
    }

    /**
     * 判断用户这句话里是否**明确指定了规格/装量**。
     * 用于决定「能不能直接拟单」——用户没说规格时，不能让模型替他挑一个默认规格。
     *
     * 判定依据：出现装量单位或规格类别词。
     * 例如「就大红袍吧」→ false（该弹规格卡片）；
     *     「大红袍 标准装100g」→ true（可以直接拟单）。
     */
    private boolean specMentionedByUser(String q) {
        if (q == null || q.isBlank()) return false;
        // 装量：数字 + g/克/斤/两/盒/罐/袋
        if (java.util.regex.Pattern.compile("\\d+\\s*(g|G|克|kg|KG|斤|两|ml|ML|毫升|片|颗|罐|袋|盒)")
                .matcher(q).find()) return true;
        // 规格类别词
        return containsAny(q, "品鉴装", "标准装", "礼盒装", "家庭装", "收藏装", "散装", "罐装",
                "袋装", "小罐", "大包装", "小包装", "体验装", "试用装");
    }

    /**
     * 查一次购物车，压成一行摘要；空购物车返回 null。
     * 供「裸确认」兜底判断场景用（用户在确认加购时，要答购物车状态而不是"没有订单草稿"）。
     * 同样按 cartItemId 去重——老项目视图会把一条 cart_item 放大成 N 行。
     */
    private String cartSummaryHint() {
        try {
            String cartJson = tools.execute("view_cart", "{}");
            JsonNode list = mapper.readTree(cartJson).path("VCartDetailEnhancedList");
            if (!list.isArray() || list.isEmpty()) return null;
            java.util.LinkedHashMap<Long, String> dedup = new java.util.LinkedHashMap<>();
            double total = 0;
            for (JsonNode it : list) {
                long cid = it.path("cartItemId").asLong();
                if (cid <= 0 || dedup.containsKey(cid)) continue;
                dedup.put(cid, it.path("productName").asText() + "（" + it.path("specName").asText()
                        + "）×" + it.path("quantity").asInt());
                total += it.path("subtotal").asDouble();
            }
            if (dedup.isEmpty()) return null;
            return String.join("、", dedup.values()) + "，合计 ¥" + (Math.round(total * 100) / 100.0);
        } catch (Exception e) {
            log.debug("查询购物车摘要失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断工具返回是否代表「鉴权失效」。
     *
     * 必须严格按结构化语义判断，**禁止裸子串匹配 "401"**——业务数据里完全可能出现 401：
     * 实测老项目地址数据含 districtCode="340102"，子串匹配会把正常查询误判为登录过期。
     *
     * 判定依据（任一命中）：
     *  1. Feign 降级返回的结构化标记 authFailed=true（TearSpringClientFallback 产生）；
     *  2. JSON 顶层 code 字段等于 401（老项目统一的 Result.code）；
     *  3. 出现明确的鉴权错误文案（如"登录已过期"/"token 无效"）。
     */
    private boolean isAuthFailure(String output) {
        if (output == null || output.isBlank()) return false;
        String s = output.trim();

        // 1) 降级工厂的结构化标记（最可靠）
        try {
            JsonNode node = mapper.readTree(s);
            if (node.path("authFailed").asBoolean(false)) return true;
            // 2) Result.code == 401
            JsonNode code = node.path("code");
            if (code.isNumber() && code.asInt() == 401) return true;
            if (code.isTextual() && "401".equals(code.asText().trim())) return true;
        } catch (Exception ignored) {
            // 非 JSON（例如纯文本错误），落到下面的文案判断
        }

        // 3) 明确的鉴权失效文案（老项目/网关的固定措辞）
        return containsAny(s, "登录已过期", "登录已失效", "token无效", "token 无效", "未登录", "请重新登录");
    }

    /**
     * 判断回答是否在「口头拟单」：复述了**完整订单草稿**并引导用户确认，但实际没走 prepare_order。
     *
     * ⚠️ 判定必须严格，否则会误伤正常提问。实测踩过：模型问
     * 「您的地址列表里有 7 条，请确认用哪个：」——这是**合理的澄清提问**（用户有多个地址需选一个），
     * 却因为命中「确认」+「地址」被判成假拟单拦掉，导致用户永远走不到拟单。
     *
     * 真·假拟单的特征是**复述了订单要素的完整清单**，因此要求同时满足：
     *  ① 有拟单语义（拟单/订单草稿/确认下单/令牌）；
     *  ② 明确列了「商品」这一项（不是泛泛提到）；
     *  ③ 同时给出金额（合计或单价）**和**收货信息（收货地址/收货人）。
     * 「请确认用哪个地址」只满足①，不满足②③，因此不会被拦截。
     */
    private boolean looksLikeDraftClaim(String answer) {
        if (answer == null || answer.isBlank()) return false;
        boolean draftWords = containsAny(answer, "拟单", "订单草稿", "确认下单", "令牌");
        if (!draftWords) return false;
        boolean hasProductLine = containsAny(answer, "商品：", "商品:", "- 商品", "· 商品", "商品 ");
        boolean hasAmount = containsAny(answer, "合计", "单价", "总价", "¥");
        boolean hasAddress = containsAny(answer, "收货地址", "收货人", "送至", "寄往");
        return hasProductLine && hasAmount && hasAddress;
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
