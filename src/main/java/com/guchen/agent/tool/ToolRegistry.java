package com.guchen.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guchen.agent.client.TearSpringClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具注册中心：把老项目 tearSpring 的接口封装成 LLM 可调用的 Function Calling 工具。
 *
 * 设计要点（面试可讲）：
 *  1. 意图路由只投喂 3-5 个候选工具的 schema，而不是把 54 个接口全塞进 prompt —— 控制 token 成本；
 *  2. 写操作工具带幂等键，避免 LLM 重试导致重复下单/重复扣券；
 *  3. 只读工具直接放行，写工具需二次确认（见 AgentOrchestrator）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final TearSpringClient tearSpring;
    private final TradeToolService trade;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 工具名 → 定义 */
    private final Map<String, ToolDefinition> registry = new LinkedHashMap<>();

    public record ToolDefinition(
            String name,
            String description,
            ObjectNode parameters,
            boolean writeOperation,
            Function<JsonNode, String> executor) {}

    /**
     * 惰性初始化，避免 @PostConstruct 期间 Feign 未就绪。
     */
    private synchronized void ensureInitialized() {
        if (!registry.isEmpty()) return;

        // ---------- 只读工具 ----------
        // 老项目 /product/shopSpecList 无关键词参数、恒返回全量商品（实测 48 条 / 约 3000 prompt tokens），
        // 因此在这里做 agent 侧关键词过滤，最多返回 10 条——修掉「假搜索」并压 token 成本
        register("search_tea", "按关键词搜索茶叶商品，返回匹配的商品列表（最多 10 条）。"
                + "每条商品的 specs 字段列出它**全部可用规格**（specId/specName/price/stock）；"
                + "顶层 spec/price 只是默认规格的展示副本。判断某规格是否存在必须以 specs 为准——"
                + "默认规格是别的装量，不代表其他规格不存在，禁止据此回绝用户。"
                + "⚠️ 关键词没有精确匹配时，本工具会自动按相近词召回**同类商品**，"
                + "并在 fuzzyNote 里说明。此时必须如实告知用户「没有叫 X 的商品，但有相近的」"
                + "并列出候选让用户选，**禁止谎称找到、禁止直接替用户下单**。"
                + "命中数较多时可能未附 specs，此时用 get_tea_detail 查看该商品的全部规格。",
                params(Map.of("keyword", "string"), List.of("keyword")), false,
                args -> searchTea(args.path("keyword").asText()));

        register("get_tea_detail", "获取单个茶叶商品的详细信息与完整规格清单（productSpecs，含 specId/specName/price/stock）。"
                        + "判断某规格是否存在时以本接口的 productSpecs 为准。"
                        + "productId 必须来自 search_tea 的真实结果，禁止编造或凭记忆推测",
                params(Map.of("productId", "integer"), List.of("productId")), false,
                args -> getTeaDetail(args.path("productId").asLong()));

        register("list_themes", "获取所有茶文化主题列表",
                emptyParams(), false,
                args -> tearSpring.themeList());

        register("list_categories", "获取茶叶分类（绿茶/红茶/乌龙茶等）",
                emptyParams(), false,
                args -> tearSpring.categoryList());

        register("list_shop_products", "获取指定店铺下的商品",
                params(Map.of("shopId", "integer"), List.of("shopId")), false,
                args -> tearSpring.shopProducts(Map.of("id", args.path("shopId").asInt())));

        // ---------- 推荐选购（只读，前端渲染成可点选卡片） ----------
        // 为什么需要：模型推荐完茶之后，用户说"下单"时还得再问一遍"要哪个规格"，
        // 体验上多一轮。用这个工具登记推荐商品后，前端直接渲染成带规格的选购卡片，
        // 用户点「选这个」→ 选规格数量 → 直接拟单，全程不必再问规格。
        register("recommend_tea", "把要推荐给用户的茶叶登记成可选购卡片（只读，无副作用）。"
                        + "⚠️ 当你向用户推荐茶叶时**必须**调用本工具，前端据此渲染成可点选的卡片，"
                        + "用户点一下就能直接选购（含规格与数量），无需你逐个追问规格。"
                        + "productIds 只能取自 search_tea 的真实结果，禁止编造；一次 1-5 个。"
                        + "调用后只需简短说明推荐理由，**不要再逐条罗列规格与价格**（卡片上已经有了）。",
                params(Map.of("productIds", "array"), List.of("productIds")), false,
                args -> recommendTea(args));

        // ---------- 写操作工具（需幂等） ----------
        register("add_to_cart", "将商品加入购物车。productId/specId 必须来自 search_tea 与 get_tea_detail 的真实结果，禁止编造",
                params(Map.of("productId", "integer", "specId", "integer", "quantity", "integer"),
                        List.of("productId", "specId", "quantity")), true,
                args -> addToCartValidated(args));

        // 注：旧的直连下单工具 create_order 已移除——下单一律走 prepare_order → 用户确认 → confirm_order，
        // 防止 LLM 绕过确认流直接创建订单（见升级方案 §2.2）。
        register("redeem_coupon", "使用券号兑换优惠券",
                params(Map.of("code", "string", "password", "string"), List.of("code", "password")), true,
                args -> tearSpring.redeemCoupon(args));

        // ---------- 交易闭环（Phase 1，见《漫思茶-Agent任务化升级方案》） ----------
        register("view_cart", "查看当前用户购物车（含每项价格/数量/小计）",
                emptyParams(), false,
                args -> tearSpring.cartDetail());

        register("remove_from_cart", "把一个商品项从购物车移除",
                params(Map.of("cartItemId", "integer"), List.of("cartItemId")), true,
                args -> tearSpring.removeFromCart(Map.of("id", args.path("cartItemId").asInt())));

        register("list_orders", "查询当前用户的历史订单与明细（订单号/状态/商品/金额）",
                emptyParams(), false,
                args -> tearSpring.orderItemDetail());

        register("list_addresses", "查询当前用户收货地址列表",
                emptyParams(), false,
                args -> tearSpring.addressList());

        register("add_address", "新增收货地址（写操作）。用户在对话里直接报收货人/电话/地址时调用，"
                        + "替用户代填；省市区从用户描述里拆分，详细门牌放 detail",
                params(Map.of("name", "string", "phone", "string", "province", "string",
                                "city", "string", "district", "string", "detail", "string", "tag", "string"),
                        List.of("name", "phone", "province", "city", "district", "detail")), true,
                args -> addAddress(args));

        register("list_coupons", "查询当前用户已领取的优惠券",
                emptyParams(), false,
                args -> tearSpring.couponUserList());

        // 取地址列表供「修改收货地址」弹层使用。
        // 注意：**不要**在下单流程里主动调它去"确认地址"——地址默认直接用，
        // 反复询问地址会让用户多次确认（实测用户反馈「重复询问太多次」）。
        // 只有用户明确说「换地址/改地址/修改地址」时才需要。
        register("request_address_pick", "查询收货地址列表。⚠️ 仅当用户明确要求「换地址/改地址/修改地址」时调用；"
                        + "下单流程中**不要**为了确认地址而调用它——地址默认直接用默认地址，"
                        + "确认弹窗上会展示地址并允许用户点「修改」。调用后前端会弹出地址选择弹层。",
                emptyParams(), false,
                args -> tearSpring.addressList());

        register("user_profile", "查询当前登录用户的个人信息",
                emptyParams(), false,
                args -> tearSpring.userData());

        // ---------- 下单确认流（human-in-the-loop） ----------
        register("prepare_order", "拟单：按商品/规格/数量/地址生成待确认的订单草稿与一次性确认令牌。"
                        + "不会真正下单。specId 不要猜测：用户未明确规格时必须省略（自动取默认规格）；"
                        + "要指定规格时 specId 只能取自 get_tea_detail 返回的 productSpecs。"
                        + "返回的 draft 必须完整复述给用户并等待确认。",
                params(Map.of("productId", "integer", "specId", "integer", "quantity", "integer", "addressId", "integer"),
                        List.of("productId")), false,
                args -> trade.prepareOrder(args));

        register("confirm_order", "确认执行下单：用户明确同意后，凭 prepare_order 返回的 confirmToken 创建真实订单。"
                        + "令牌一次性，重复调用会失败。",
                params(Map.of("confirmToken", "string"), List.of("confirmToken")), true,
                args -> trade.confirmOrder(args));

        register("cancel_order", "取消拟单：用户反悔时作废确认令牌",
                params(Map.of("confirmToken", "string"), List.of("confirmToken")), true,
                args -> trade.cancelOrder(args));

        log.info("工具注册完成，共 {} 个工具", registry.size());
    }

    private void register(String name, String desc, ObjectNode params,
                          boolean writeOp, Function<JsonNode, String> executor) {
        registry.put(name, new ToolDefinition(name, desc, params, writeOp, executor));
    }

    public ToolDefinition get(String name) {
        ensureInitialized();
        return registry.get(name);
    }

    public boolean isWriteOperation(String name) {
        ToolDefinition d = get(name);
        return d != null && d.writeOperation();
    }

    /**
     * 生成 OpenAI function calling 格式的 tools 数组。
     *
     * @param allowNames 意图路由筛出的候选工具名；为 null 时返回全部
     */
    public ArrayNode buildToolsSchema(List<String> allowNames) {
        ensureInitialized();
        ArrayNode arr = mapper.createArrayNode();
        for (ToolDefinition d : registry.values()) {
            if (allowNames != null && !allowNames.contains(d.name())) continue;
            ObjectNode tool = arr.addObject();
            tool.put("type", "function");
            ObjectNode fn = tool.putObject("function");
            fn.put("name", d.name());
            fn.put("description", d.description());
            fn.set("parameters", d.parameters());
        }
        return arr;
    }

    /** 执行工具调用，异常统一转成给 LLM 看的错误文本（而不是抛出去打断循环）。 */
    public String execute(String name, String argsJson) {
        ToolDefinition def = get(name);
        if (def == null) {
            return "{\"error\":\"未注册的工具: " + name + "\"}";
        }
        try {
            JsonNode args = mapper.readTree(argsJson);
            String result = def.executor().apply(args);
            log.debug("工具 {} 执行成功", name);
            return result;
        } catch (Exception e) {
            log.warn("工具 {} 执行失败: {}", name, e.getMessage());
            String msg = String.valueOf(e.getMessage())
                    .replace("\\", "/").replace("\"", "'").replace("\n", " ").replace("\r", " ");
            // Feign fallbackFactory 在未启用 circuit breaker 时不生效（本项目即此配置），
            // 401 等异常会直接抛到这里——在此打鉴权标记供编排器短路。
            // ⚠️ 匹配必须带上下文（"[401]" / "401 Unauthorized"），
            // 裸 "401" 会误伤业务数据（实测地址 districtCode=340102 含 401 子串）。
            boolean authFailed = msg.contains("[401]") || msg.contains("401 Unauthorized")
                    || msg.contains("401 unauthorized");
            ObjectNode errNode = mapper.createObjectNode();
            errNode.put("error", msg);
            errNode.put("authFailed", authFailed);
            return json(errNode);
        }
    }

    public java.util.Set<String> allToolNames() {
        ensureInitialized();
        return registry.keySet();
    }

    // ==================== 商品 id/specId 强校验（防 LLM 编造） ====================

    /**
     * 详情查询前先验 id 真实性。老项目 /product/selectSingle 对不存在的 id
     * 会静默返回空规格（不报错），LLM 只能对着空数据发呆——必须在工具层拦下，
     * 并给出「重新 search_tea」的可行动指引。
     */
    private String getTeaDetail(long productId) {
        JsonNode product = findProductInList(productId);
        if (product == null) {
            return err("商品 id=" + productId + " 不存在。productId 必须来自 search_tea 返回的真实结果——"
                    + "请按商品名重新调用 search_tea 获取正确 id，不要凭记忆或推测编造");
        }
        return tearSpring.productDetail(Map.of("id", (int) productId));
    }

    /**
     * 推荐选购：把商品登记成前端可渲染的选购卡片。
     * 返回结构与 searchTea 的 specs 一致，前端直接拿来做规格选择。
     * 失败项不抛错（跳过即可），保证推荐主流程不被个别商品拖垮。
     */
    private String recommendTea(JsonNode args) {
        JsonNode ids = args.path("productIds");
        if (!ids.isArray() || ids.isEmpty()) return err("productIds 必填，且必须来自 search_tea 的真实结果");

        ArrayNode cards = mapper.createArrayNode();
        int limit = Math.min(ids.size(), 5);
        for (int i = 0; i < limit; i++) {
            long pid = ids.get(i).asLong(0);
            if (pid <= 0) continue;
            JsonNode product = findProductInList(pid);
            if (product == null) {
                log.debug("推荐跳过不存在的商品 id={}", pid);
                continue;
            }
            ObjectNode card = mapper.createObjectNode();
            card.put("productId", pid);
            card.put("productName", product.path("name").asText());
            card.put("productEmoji", product.path("emoji").asText());
            card.put("subtitle", product.path("subtitle").asText());
            card.put("origin", product.path("originDetail").asText());
            // 完整规格清单（前端据此渲染规格选择，避免再问用户一遍）
            JsonNode specs = loadSpecs(pid);
            ArrayNode specArr = mapper.createArrayNode();
            if (specs != null && specs.isArray()) {
                for (JsonNode s : specs) {
                    ObjectNode one = mapper.createObjectNode();
                    one.put("specId", s.path("id").asLong());
                    one.put("specName", s.path("specName").asText());
                    one.put("price", s.path("price").asDouble());
                    one.put("stock", s.path("stock").asInt());
                    one.put("isDefault", s.path("isDefault").asInt());
                    specArr.add(one);
                }
            }
            if (specArr.isEmpty()) continue;
            card.set("specs", specArr);
            cards.add(card);
        }
        if (cards.isEmpty()) {
            return err("没有可推荐的商品（productIds 必须来自 search_tea 的真实结果）");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("action", "RECOMMENDED");
        out.put("count", cards.size());
        out.put("instruction", "已生成可选购卡片。请只用一两句话说明推荐理由，"
                + "**不要**再逐条罗列规格和价格——卡片上已经展示，重复会让用户觉得啰嗦。");
        out.set("cards", cards);
        return json(out);
    }

    /** 加购前校验：商品存在 + specId 属于该商品（否则老项目会加进一个幽灵组合）。 */
    private String addToCartValidated(JsonNode args) {
        long productId = args.path("productId").asLong(0);
        long specId = args.path("specId").asLong(0);
        int quantity = args.path("quantity").asInt(0);
        if (productId <= 0) return err("productId 必填（来自 search_tea 真实结果）");
        JsonNode product = findProductInList(productId);
        if (product == null) {
            return err("商品 id=" + productId + " 不存在。请按商品名重新调用 search_tea 获取正确 id");
        }
        JsonNode specs;
        try {
            specs = mapper.readTree(tearSpring.productDetail(Map.of("id", (int) productId))).path("productSpecs");
        } catch (Exception e) {
            return err("查询规格失败: " + e.getMessage());
        }
        boolean specOk = false;
        StringBuilder avail = new StringBuilder();
        for (JsonNode s : specs) {
            avail.append(s.path("id").asLong()).append("=").append(s.path("specName").asText()).append(" ");
            if (s.path("id").asLong() == specId) specOk = true;
        }
        if (!specOk) {
            return err("规格 id=" + specId + " 不属于商品「" + product.path("name").asText()
                    + "」。可用规格: " + avail.toString().trim() + "——请从 get_tea_detail 的 productSpecs 里选");
        }
        if (quantity <= 0) return err("数量需大于 0");
        ObjectNode body = mapper.createObjectNode()
                .put("productId", productId).put("specId", specId)
                .put("quantity", quantity).put("checked", 1);
        return tearSpring.addToCart(body);
    }

    // ==================== agent 侧搜索过滤 ====================

    private static final int SEARCH_MAX_RESULTS = 10;

    /**
     * 规格列表的补全上限：命中数不超过这个值时，逐个回查真实规格并挂到结果上。
     *
     * 为什么需要补全（真实缺陷修复）：
     *  老项目 /product/shopSpecList 每条商品只带**默认规格的扁平字段**（`spec`/`price`/`stock`），
     *  既没有 specId、也没有其他规格。LLM 拿到这份数据后会得出「该商品只有这一个规格」的结论，
     *  进而**否定用户已经明确提出的、实际存在的其他规格**（实测：用户要「武功山云雾·品鉴装50g」，
     *  spec_id=9 真实存在（¥98/库存30），但模型因搜索结果只显示「标准装100g」而回复
     *  「并无品鉴装50g，我无法按此下单」——把用户卡死在死循环里）。
     *  补全后模型手里有完整规格，此类误判从源头消失。
     */
    private static final int SPEC_ENRICH_MAX = 5;

    /** 规格缓存：productId → 规格数组 JSON。TTL 5 分钟，避免同一次会话反复回查老项目。 */
    private final Map<Long, CachedSpecs> specCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SPEC_CACHE_MS = 5 * 60 * 1000L;

    private record CachedSpecs(JsonNode specs, long at) {}

    /**
     * 拉全量商品后按关键词过滤（匹配整条 JSON 文本，对字段命名不敏感），
     * 返回结构与原始 ShopSpecList 一致并附 matched/totalScanned 供 LLM 判断。
     * 空关键词返回封顶的全量列表。
     *
     * 额外：命中数较少时，为每条商品补上**完整规格列表**（specs: [{specId, specName, price, stock, isDefault}]），
     * 让 LLM 不必（也不应）依赖默认规格去推断「有没有其他规格」。
     */
    private String searchTea(String keyword) {
        String raw = tearSpring.searchProductRaw();
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode list = root.path("ShopSpecList");
            if (!list.isArray()) return raw;

            String kw = keyword == null ? "" : keyword.trim().toLowerCase();
            ArrayNode filtered = mapper.createArrayNode();
            int scanned = list.size();
            String matchedBy = "";
            if (kw.isEmpty()) {
                for (JsonNode item : list) {
                    if (filtered.size() >= SEARCH_MAX_RESULTS) break;
                    filtered.add(item);
                }
            } else {
                // 分级降级匹配：整串 → 去掉修饰词 → 核心品类词
                // 为什么需要（真实缺陷）：用户说「雨前龙井」，而库里只有「明前龙井」，
                // 整串子串匹配直接 0 命中，模型只能说「没找到这个商品」并让用户重搜——
                // 但正确的做法是把**同品类的真实商品**摆出来让用户选（明前/雨前是同类茶的采制时节差异）。
                // 逐级放宽召回：先精确，再退到品类，绝不跨品类乱猜。
                for (String variant : keywordVariants(kw)) {
                    for (JsonNode item : list) {
                        if (filtered.size() >= SEARCH_MAX_RESULTS) break;
                        if (item.toString().toLowerCase().contains(variant)) filtered.add(item);
                    }
                    if (filtered.size() > 0) {
                        matchedBy = variant.equals(kw) ? "整串精确" : ("降级词「" + variant + "」");
                        break;   // 一旦命中就不再放宽，保证精确优先
                    }
                }
            }

            // 规格补全：只在命中数少时做（搜索场景通常精准），避免命中 10 条时打 10 次老项目接口
            boolean enriched = false;
            if (filtered.size() > 0 && filtered.size() <= SPEC_ENRICH_MAX) {
                for (int i = 0; i < filtered.size(); i++) {
                    JsonNode item = filtered.get(i);
                    ObjectNode patched = attachSpecs(item);
                    if (patched != null) {
                        filtered.set(i, patched);
                        enriched = true;
                    }
                }
            }

            ObjectNode out = mapper.createObjectNode();
            out.put("msg", kw.isEmpty() ? "返回全部商品（已截断）" : "按关键词「" + keyword + "」过滤商品");
            out.put("code", 200);
            out.put("matched", filtered.size());
            out.put("totalScanned", scanned);
            if (!kw.isEmpty()) out.put("matchedBy", matchedBy);
            // 降级命中时明确提示：用户原词没有精确匹配，这是同品类的近似结果，
            // 模型必须如实说明「没有完全一致的，但有相近的」并把选择权交给用户，
            // 禁止谎称"找到了"或擅自替用户决定。
            if (!kw.isEmpty() && !"整串精确".equals(matchedBy) && filtered.size() > 0) {
                out.put("fuzzyNote", "用户原关键词「" + keyword + "」在商品库里**没有精确匹配**，"
                        + "以上是按相近词「" + matchedBy + "」召回的**同类商品**。"
                        + "回复时必须如实说明：没有叫「" + keyword + "」的商品，"
                        + "但有下列相近的（列出名称/规格/价格），请用户确认要哪一个再拟单。"
                        + "⚠️ 禁止把它们说成就是用户要的那款，也禁止不说明差异就直接下单。");
            }
            if (enriched) {
                out.put("specsNote", "本次已在每条商品的 specs 字段附带该商品**全部可用规格**"
                        + "（specId/specName/price/stock/isDefault）。"
                        + "顶层 spec/price 仅为默认规格的展示副本；"
                        + "判断「有没有某个规格」必须以 specs 为准，禁止因为默认规格不同就断定规格不存在。");
            }
            if (filtered.isEmpty()) {
                out.put("hint", "没有匹配的商品。可尝试：①换更宽泛的品类词（如「龙井」而不是「雨前龙井」）；"
                        + "②调用 list_categories 看有哪些品类。不要凭空推荐不存在的商品。");
            }
            out.set("ShopSpecList", filtered);
            log.debug("search_tea 关键词[{}]：扫描 {} 条，命中 {} 条，规格补全={}，匹配方式={}",
                    keyword, scanned, filtered.size(), enriched, matchedBy.isEmpty() ? "-" : matchedBy);
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("search_tea 过滤失败，退回原始结果: {}", e.getMessage());
            return raw;
        }
    }

    /**
     * 生成关键词的降级候选（从精确到宽松，顺序即优先级）。
     *
     * 背景（真实缺陷）：用户说「雨前龙井」→ 库里只有「明前龙井」→ 整串匹配 0 命中，
     * 模型只能回「没找到这个商品」并让用户重搜，体验断裂。
     * 明前/雨前是**同一款茶的不同采制时节**，应当能召回彼此；
     * 但降级必须克制：只去掉「时节/等级/包装」这类修饰词，退到**品类词**为止，
     * 绝不跨品类联想（「龙井」不能退化成「绿茶」把所有绿茶都倒出来，那会淹没有效信息）。
     *
     * 策略（按顺序尝试，首个命中即止）：
     *  ① 原词整串
     *  ② 去掉前缀修饰（雨前/明前/特级/一级/新茶/陈茶/正品/野生/高山/礼盒…）
     *  ③ 去掉后缀修饰（礼盒装/特级/散装…）
     *  ④ 核心品类词（去掉所有修饰后的剩余部分，长度 ≥2 才用）
     */
    private List<String> keywordVariants(String kw) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(kw);

        // 修饰词表：只收敛到「不影响品类判断」的限定词
        String[] affixes = {
                "雨前", "明前", "清明前", "谷雨前",
                "特级", "一级", "二级", "三级", "优级", "头采", "明后",
                "新茶", "陈茶", "春茶", "秋茶", "当季",
                "正品", "野生", "高山", "古树", "原产", "正宗", "精选",
                "礼盒装", "礼盒", "散装", "罐装", "袋装", "小罐", "盒装"
        };

        // ② 去前缀
        for (String a : affixes) {
            if (kw.startsWith(a) && kw.length() > a.length() + 1) {
                out.add(kw.substring(a.length()));
            }
        }
        // ③ 去后缀
        for (String a : affixes) {
            if (kw.endsWith(a) && kw.length() > a.length() + 1) {
                out.add(kw.substring(0, kw.length() - a.length()));
            }
        }
        // ④ 全去修饰后的核心词（仅当剩余长度 ≥2，避免单字乱匹配）
        String core = kw;
        for (String a : affixes) core = core.replace(a, "");
        if (core.length() >= 2 && !core.equals(kw)) out.add(core);

        // 过滤空串与单字（单字匹配噪音太大）
        return out.stream().filter(s -> s != null && s.length() >= 2).toList();
    }

    /**
     * 给一条商品补上完整规格。失败时返回 null（保持原样，不影响搜索主流程）。
     * 走 5 分钟缓存，避免同一商品反复回查老项目。
     */
    private ObjectNode attachSpecs(JsonNode item) {
        long pid = item.path("id").asLong(0);
        if (pid <= 0 || !item.isObject()) return null;
        try {
            JsonNode specs = loadSpecs(pid);
            if (specs == null || !specs.isArray() || specs.isEmpty()) return null;

            ObjectNode copy = ((ObjectNode) item).deepCopy();
            ArrayNode slim = mapper.createArrayNode();
            for (JsonNode s : specs) {
                ObjectNode one = mapper.createObjectNode();
                one.put("specId", s.path("id").asLong());
                one.put("specName", s.path("specName").asText());
                one.put("price", s.path("price").asDouble());
                one.put("stock", s.path("stock").asInt());
                one.put("isDefault", s.path("isDefault").asInt());
                slim.add(one);
            }
            copy.set("specs", slim);
            return copy;
        } catch (Exception e) {
            log.debug("商品 {} 规格补全失败（保留默认展示）: {}", pid, e.getMessage());
            return null;
        }
    }

    /** 读商品规格（带缓存）：productId → productSpecs 数组。失败返回 null（调用方降级）。 */
    private JsonNode loadSpecs(long productId) {
        CachedSpecs cached = specCache.get(productId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < SPEC_CACHE_MS) return cached.specs();

        JsonNode specs;
        try {
            specs = mapper.readTree(
                    tearSpring.productDetail(Map.of("id", (int) productId))).path("productSpecs");
        } catch (Exception e) {
            log.debug("读取商品 {} 规格失败: {}", productId, e.getMessage());
            return null;
        }
        specCache.put(productId, new CachedSpecs(specs, now));
        return specs;
    }

    private String err(String msg) {
        try {
            return mapper.writeValueAsString(mapper.createObjectNode().put("error", msg));
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    private String json(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    /** 在全量商品列表里定位商品（校验 id 真实性用）；找不到返回 null。 */
    private JsonNode findProductInList(long productId) {
        try {
            JsonNode list = mapper.readTree(tearSpring.searchProductRaw()).path("ShopSpecList");
            for (JsonNode item : list) {
                if (item.path("id").asLong() == productId) return item;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================== 收货地址代填 ====================

    /**
     * 新增收货地址。老项目 addressUpdateInsert 按 id 是否存在自动区分插入/更新，
     * userId 从 token 取（不信任传参）；新地址置为默认，旧默认自动让位（service 内已处理）。
     * 省市区编码列是自由文本，对话场景填空串即可（App 内手动添加才带编码）。
     */
    private String addAddress(JsonNode args) {
        String name = args.path("name").asText("").trim();
        String phone = args.path("phone").asText("").trim();
        String province = args.path("province").asText("").trim();
        String city = args.path("city").asText("").trim();
        String district = args.path("district").asText("").trim();
        String detail = args.path("detail").asText("").trim();
        String tag = args.path("tag").asText("").trim();

        if (name.isEmpty()) return err("收货人姓名必填");
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 5 || digits.length() > 15) return err("电话格式不正确: " + phone);
        if (detail.isEmpty()) return err("详细地址（街道/门牌）必填");
        if (province.isEmpty() && city.isEmpty() && district.isEmpty()) {
            return err("省/市/区至少提供一项（从用户描述里拆分，如「江西省」「南昌市」「南昌大学」）");
        }

        // 去重：同电话同 fullAddress 已存在时直接复用，不再插入（防对话重试产生重复地址）
        try {
            JsonNode arr = mapper.readTree(tearSpring.addressList()).path("addressList");
            for (JsonNode a : arr) {
                if (a.path("phone").asText("").equals(digits.length() == 11 ? digits : phone)
                        && a.path("fullAddress").asText("").equals(province + city + district + detail)) {
                    ObjectNode dup = mapper.createObjectNode();
                    dup.put("action", "EXISTS");
                    dup.put("addressId", a.path("id").asLong());
                    dup.put("fullAddress", a.path("fullAddress").asText());
                    dup.put("note", "该地址已存在，直接复用，未重复创建");
                    return json(dup);
                }
            }
        } catch (Exception ignored) {
        }

        ObjectNode body = mapper.createObjectNode()
                .put("name", name)
                .put("phone", digits.length() == 11 ? digits : phone)
                .put("tag", tag.isEmpty() ? "家" : tag)
                .put("province", province).put("provinceCode", "")
                .put("city", city).put("cityCode", "")
                .put("district", district).put("districtCode", "")
                .put("detail", detail)
                .put("fullAddress", province + city + district + detail)
                .put("isDefault", 1);
        return tearSpring.addressUpsert(body);
    }

    // ==================== JSON Schema 构造辅助 ====================

    private ObjectNode emptyParams() {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", "object");
        p.set("properties", mapper.createObjectNode());
        return p;
    }

    private ObjectNode params(Map<String, String> props, List<String> required) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", "object");
        ObjectNode properties = p.putObject("properties");
        props.forEach((k, type) -> {
            ObjectNode one = properties.putObject(k);
            if ("array".equals(type)) {
                one.put("type", "array");
                one.putObject("items").put("type", "integer");
            } else {
                one.put("type", type);
            }
        });
        ArrayNode req = p.putArray("required");
        required.forEach(req::add);
        return p;
    }
}
