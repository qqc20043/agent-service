package com.guchen.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guchen.agent.client.TearSpringClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户画像：从真实订单明细（老项目 VOrderItemDetail 视图）规则抽取消费画像，
 * 生成一句可注入 system prompt 的上下文——让「根据购买记录推荐」有据可依。
 *
 * 设计取舍：
 *  - 规则抽取而不是 LLM 抽取：可解释、零 token 成本、字段稳定；
 *  - 缓存 7 天 + 下单后主动失效（invalidate）：订单变化即时反映到下一轮对话；
 *  - 每次重建都带当前 token 调老项目：token 过期时自然降级为无画像（不缓存失败结果）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final TearSpringClient tearSpring;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String KEY_PROFILE = "ms:agent:profile:";
    private static final Duration PROFILE_TTL = Duration.ofDays(7);

    /** 读取画像上下文（一句话）；无订单/构建失败返回空串。缓存 7 天。 */
    public String profileContext(String token, long userId) {
        String key = KEY_PROFILE + userId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) return cached;
            String line = rebuild(token);
            if (line == null || line.isBlank()) return "";
            redis.opsForValue().set(key, line, PROFILE_TTL);
            return line;
        } catch (Exception e) {
            log.debug("读取用户画像失败 userId={}: {}", userId, e.getMessage());
            return "";
        }
    }

    /** 下单成功后调用：清掉缓存画像，下一轮对话自动按新订单重建。 */
    public void invalidate(long userId) {
        if (userId > 0) redis.delete(KEY_PROFILE + userId);
    }

    /** 拉订单明细并规则抽取画像（依赖调用方已通过 FeignAuthConfig 设置 token）。 */
    private String rebuild(String token) {
        try {
            JsonNode root = mapper.readTree(tearSpring.orderItemDetail());
            JsonNode list = root.path("vOrderItemDetailList");
            if (!list.isArray() || list.isEmpty()) return null;

            Set<Long> orderIds = new HashSet<>();
            Map<String, Integer> productCount = new LinkedHashMap<>();
            double minPrice = Double.MAX_VALUE, maxPrice = 0;
            String lastTime = "", lastProduct = "";

            for (JsonNode r : list) {
                String name = r.path("productName").asText("");
                if (name.isEmpty()) continue;
                orderIds.add(r.path("orderId").asLong());
                productCount.merge(name, 1, Integer::sum);
                double price = r.path("unitPrice").asDouble();
                if (price > 0) {
                    minPrice = Math.min(minPrice, price);
                    maxPrice = Math.max(maxPrice, price);
                }
                String t = r.path("orderCreatedAt").asText("");
                if (t.compareTo(lastTime) >= 0) {
                    lastTime = t;
                    lastProduct = name;
                }
            }
            if (orderIds.isEmpty()) return null;

            String top = productCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining("、"));

            String minStr = minPrice == Double.MAX_VALUE ? "?" : String.valueOf(minPrice);
            return "历史订单 " + orderIds.size() + " 笔；常购商品：" + top
                    + "；单价区间 ¥" + minStr + "-" + maxPrice
                    + "；最近一次购买：" + lastProduct
                    + "（" + (lastTime.length() >= 10 ? lastTime.substring(0, 10) : lastTime) + "）"
                    + "。做推荐时可引用以上真实记录，禁止编造购买历史。";
        } catch (Exception e) {
            log.debug("构建用户画像失败: {}", e.getMessage());
            return null;
        }
    }
}
