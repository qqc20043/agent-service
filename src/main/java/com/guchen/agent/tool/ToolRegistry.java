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
        register("search_tea", "按关键词搜索茶叶商品，返回商品列表",
                params(Map.of("keyword", "string"), List.of("keyword")), false,
                args -> tearSpring.searchProduct(args.path("keyword").asText()));

        register("get_tea_detail", "获取单个茶叶商品的详细信息与规格",
                params(Map.of("productId", "integer"), List.of("productId")), false,
                args -> tearSpring.productDetail(Map.of("id", args.path("productId").asInt())));

        register("list_themes", "获取所有茶文化主题列表",
                emptyParams(), false,
                args -> tearSpring.themeList());

        register("list_categories", "获取茶叶分类（绿茶/红茶/乌龙茶等）",
                emptyParams(), false,
                args -> tearSpring.categoryList());

        register("list_shop_products", "获取指定店铺下的商品",
                params(Map.of("shopId", "integer"), List.of("shopId")), false,
                args -> tearSpring.shopProducts(Map.of("id", args.path("shopId").asInt())));

        // ---------- 写操作工具（需幂等） ----------
        register("add_to_cart", "将商品加入购物车",
                params(Map.of("productId", "integer", "specId", "integer", "quantity", "integer"),
                        List.of("productId", "specId", "quantity")), true,
                args -> tearSpring.addToCart(args));

        register("create_order", "创建订单",
                params(Map.of("addressId", "integer", "specIds", "array"), List.of("addressId")), true,
                args -> tearSpring.createOrder(args));

        register("redeem_coupon", "使用券号兑换优惠券",
                params(Map.of("code", "string", "password", "string"), List.of("code", "password")), true,
                args -> tearSpring.redeemCoupon(args));

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
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    public java.util.Set<String> allToolNames() {
        ensureInitialized();
        return registry.keySet();
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
