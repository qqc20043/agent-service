package com.guchen.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guchen.agent.client.TearSpringClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 交易工具服务：下单确认流（human-in-the-loop）。
 *
 * 设计（面试可讲，见《漫思茶-Agent任务化升级方案》§2.2）：
 *  1. LLM 只能「提议」不能「拍板」——prepare_order 生成拟单与一次性令牌，
 *     用户明确确认后 confirm_order 才真正执行；
 *  2. confirmToken 存 Redis（TTL 5 分钟），执行用 GETDEL 原子消费——
 *     同一令牌只能执行一次，天然防 LLM 重试/重放导致的重复下单（幂等）；
 *  3. 金额、商品、规格、地址全部以服务端数据为准，LLM 传参只做「意向」，
 *     工具层强校验（库存、规格归属、数量上限）——不信任 LLM 的数字；
 *  4. 老项目下单契约（实测）：OrderData 必须带 orderNo 与 usedPoints，
 *     金额由调用方计算传入；明细的 orderId 从主单响应里取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeToolService {

    private final TearSpringClient tearSpring;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String CONFIRM_KEY_PREFIX = "agent:confirm:";
    private static final Duration CONFIRM_TTL = Duration.ofMinutes(5);
    private static final int MAX_QUANTITY = 10;

    /**
     * 原子 GET+DEL（Lua）：令牌只能被消费一次。
     * 不用 Spring 的 getAndDelete()——它走 Redis 6.2 的 GETDEL 命令，
     * 本机 Redis 3.0.504 不支持；Lua 在老版本上同样原子。
     */
    private static final org.springframework.data.redis.core.script.RedisScript<String> GETDEL_LUA =
            org.springframework.data.redis.core.script.RedisScript.of(
                    "local v = redis.call('GET', KEYS[1]); "
                            + "if v then redis.call('DEL', KEYS[1]) end; return v",
                    String.class);

    // ==================== 拟单（无副作用） ====================

    /**
     * 生成订单拟单。参数是「意向」：规格缺省取默认规格，数量缺省 1，地址缺省取默认地址。
     * 返回给 LLM 的 JSON 里带 confirmToken 与「必须向用户复述并等待确认」的指令。
     */
    public String prepareOrder(JsonNode args) {
        long productId = args.path("productId").asLong(0);
        long specId = args.path("specId").asLong(0);
        int quantity = args.path("quantity").asInt(1);
        long addressId = args.path("addressId").asLong(0);
        if (productId <= 0) return err("productId 必填（请从 search_tea 的真实结果里取，不要编造）");
        if (quantity <= 0 || quantity > MAX_QUANTITY) return err("数量需在 1-" + MAX_QUANTITY + " 之间");

        // 商品名/emoji 从全量列表里定位（shopSpecList.id 即 productId）
        JsonNode product = findProductInList(productId);
        if (product == null) return err("商品不存在：id=" + productId + "（必须使用 search_tea 返回的真实 id）");

        // 规格（价格一律以库内为准）
        JsonNode specs;
        try {
            specs = mapper.readTree(tearSpring.productDetail(Map.of("id", (int) productId))).path("productSpecs");
        } catch (Exception e) {
            return err("查询商品规格失败: " + e.getMessage());
        }
        JsonNode spec = null;
        for (JsonNode s : specs) {
            if (specId != 0 ? s.path("id").asLong() == specId : s.path("isDefault").asInt() == 1) {
                spec = s;
                break;
            }
        }
        if (spec == null && specId == 0 && specs.isArray() && !specs.isEmpty()) spec = specs.get(0);
        if (spec == null) return err("规格不存在或不属于该商品（specId=" + specId + "）");

        int stock = spec.path("stock").asInt(0);
        if (quantity > stock) return err("库存不足：仅剩 " + stock + " 件");

        // 地址（缺省取默认地址）
        JsonNode address = null;
        try {
            JsonNode arr = mapper.readTree(tearSpring.addressList()).path("addressList");
            for (JsonNode a : arr) {
                if (addressId != 0 ? a.path("id").asLong() == addressId : a.path("isDefault").asInt() == 1) {
                    address = a;
                    break;
                }
            }
            if (address == null && addressId == 0 && arr.isArray() && !arr.isEmpty()) address = arr.get(0);
        } catch (Exception e) {
            return err("查询收货地址失败: " + e.getMessage());
        }
        if (address == null) return err("未找到收货地址，请先添加地址");

        double unit = spec.path("price").asDouble();
        double subtotal = Math.round(unit * quantity * 100) / 100.0;

        String confirmToken = UUID.randomUUID().toString().replace("-", "");
        ObjectNode draft = mapper.createObjectNode();
        draft.put("productId", productId);
        draft.put("productName", product.path("name").asText());
        draft.put("productEmoji", product.path("emoji").asText());
        draft.put("specId", spec.path("id").asLong());
        draft.put("specName", spec.path("specName").asText());
        draft.put("quantity", quantity);
        draft.put("unitPrice", unit);
        draft.put("subtotal", subtotal);
        draft.put("shopId", product.path("shopId").asLong());
        draft.put("shopName", product.path("shopName").asText());
        draft.put("addressId", address.path("id").asLong());
        draft.put("addressSummary", address.path("fullAddress").asText()
                + "（" + address.path("name").asText() + " " + address.path("phone").asText() + "）");

        try {
            redis.opsForValue().set(CONFIRM_KEY_PREFIX + confirmToken, mapper.writeValueAsString(draft), CONFIRM_TTL);
        } catch (Exception e) {
            return err("拟单暂存失败: " + e.getMessage());
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("action", "PREPARED")
                .put("instruction", "这是拟单不是订单。必须把拟单内容完整复述给用户（商品/规格/数量/单价/合计/收货地址），"
                        + "并等待用户明确回复确认后，才能调用 confirm_order。用户未确认前不得调用 confirm_order。")
                .put("confirmToken", confirmToken)
                .put("expiresInMinutes", CONFIRM_TTL.toMinutes());
        out.set("draft", draft);
        return json(out);
    }

    // ==================== 确认执行（GETDEL 幂等） ====================

    /** 原子消费令牌后执行真实下单链：加购 → 主单 → 明细。 */
    public String confirmOrder(JsonNode args) {
        String token = args.path("confirmToken").asText("");
        if (token.isBlank()) return err("confirmToken 必填（来自 prepare_order 的返回）");
        String key = CONFIRM_KEY_PREFIX + token;
        String draftJson = redis.execute(GETDEL_LUA, java.util.List.of(key));
        if (draftJson == null) return err("确认令牌无效、已使用或已过期，请重新 prepare_order 拟单");

        JsonNode draft;
        try {
            draft = mapper.readTree(draftJson);
        } catch (Exception e) {
            return err("拟单数据损坏: " + e.getMessage());
        }

        long productId = draft.path("productId").asLong();
        long specId = draft.path("specId").asLong();
        int quantity = draft.path("quantity").asInt();
        double subtotal = draft.path("subtotal").asDouble();
        long shopId = draft.path("shopId").asLong();
        String shopName = draft.path("shopName").asText();

        try {
            // 注意：不下加购这一步。老项目 cart_item 的唯一键 uk_cart_product 是
            // (cart_id, product_id)——不含 spec_id，同商品换规格加购会撞唯一键 500（缺陷 #5）；
            // 且下单链路（主单+明细）并不依赖购物车，加购是画蛇添足的一步，移除后整类故障消失。

            // 1) 主单（orderNo 由我们生成——老项目不生成且该列无默认值；usedPoints 必传 0，否则 NPE）
            // 状态直接置为 unship（待发货）：本演示环境未接入真实支付，
            // 用户确认下单即视为交易成立，前端「我的订单」应显示待发货而非待付款。
            String orderNo = "ORD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + String.format("%04d", (int) (Math.random() * 10000));
            ObjectNode order = mapper.createObjectNode()
                    .put("orderNo", orderNo).put("addressId", draft.path("addressId").asLong())
                    .put("shopId", shopId).put("shopName", shopName)
                    .put("status", "unship")
                    .put("totalAmount", subtotal).put("discountAmount", 0.0)
                    .put("pointsDiscount", 0.0).put("couponDiscount", 0.0)
                    .put("deliveryFee", 0.0).put("payAmount", subtotal)
                    .put("usedPoints", 0).put("remark", "漫思茶Agent确认流下单")
                    .put("deliveryMethod", "express").put("paymentMethod", "wechat");
            String orderResp = tearSpring.createOrder(order);
            JsonNode orderResult = mapper.readTree(orderResp);
            long orderId = orderResult.path("orderId").asLong();
            if (orderId <= 0) return err("创建订单失败: " + orderResp);

            // 3) 明细
            ObjectNode item = mapper.createObjectNode()
                    .put("orderId", orderId).put("productId", productId)
                    .put("productName", draft.path("productName").asText())
                    .put("productSpec", draft.path("specName").asText())
                    .put("productEmoji", draft.path("productEmoji").asText())
                    .put("price", draft.path("unitPrice").asDouble())
                    .put("quantity", quantity).put("subtotal", subtotal);
            ArrayNode items = mapper.createArrayNode().add(item);
            tearSpring.createOrderItems(items);

            log.info("确认流下单成功 orderNo={} orderId={} pay={}", orderNo, orderId, subtotal);
            ObjectNode out = mapper.createObjectNode();
            out.put("action", "ORDER_CREATED")
                    .put("orderNo", orderNo).put("orderId", orderId)
                    .put("payAmount", subtotal).put("status", "unship")
                    .put("note", "订单已创建，状态为待发货（演示环境未接入真实支付，确认即视为交易成立）");
            return json(out);
        } catch (Exception e) {
            log.warn("确认流下单失败: {}", e.getMessage(), e);
            return err("下单执行失败: " + e.getMessage());
        }
    }

    /** 取消拟单（无副作用，只是删令牌）。 */
    public String cancelOrder(JsonNode args) {
        String token = args.path("confirmToken").asText("");
        Boolean deleted = redis.delete(CONFIRM_KEY_PREFIX + token);
        return json(mapper.createObjectNode()
                .put("action", "CANCELLED")
                .put("cancelled", Boolean.TRUE.equals(deleted)));
    }

    // ==================== 辅助 ====================

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

    private String err(String msg) {
        return json(mapper.createObjectNode().put("error", msg));
    }

    private String json(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }
}
