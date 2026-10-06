package com.guchen.agent.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Feign 降级：老项目挂掉时，Agent 不能整个 500，
 * 而是把「服务暂时不可用」作为工具执行结果交给 LLM，
 * 让 LLM 据此回复用户（这本身就是 Agent 鲁棒性的一部分）。
 */
@Slf4j
@Component
public class TearSpringClientFallback implements FallbackFactory<TearSpringClient> {

    @Override
    public TearSpringClient create(Throwable cause) {
        log.warn("tearSpring 服务调用失败，进入降级: {}", cause.getMessage());
        // 鉴权失败（token 过期/无效）单独标记：编排器据此推 auth_expired 事件、
        // 前端引导重新登录，而不是让 LLM 自由发挥道歉（确定性降级路径）
        final boolean authFailed = cause instanceof feign.FeignException fe && fe.status() == 401;
        return new TearSpringClient() {
            private String degrade(String api) {
                return "{\"degraded\":true,\"authFailed\":" + authFailed
                        + ",\"api\":\"" + api
                        + "\",\"message\":\"" + (authFailed ? "登录已过期，请重新登录" : "商品服务暂时不可用，请稍后重试")
                        + "\"}";
            }
            @Override public String productDetail(java.util.Map<String, Object> b) { return degrade("productDetail"); }
            @Override public String shopSpecList() { return degrade("shopSpecList"); }
            @Override public String teawareList() { return degrade("teawareList"); }
            @Override public String themeList() { return degrade("themeList"); }
            @Override public String themeProductList() { return degrade("themeProductList"); }
            @Override public String categoryList() { return degrade("categoryList"); }
            @Override public String shopDetail(java.util.Map<String, Object> b) { return degrade("shopDetail"); }
            @Override public String shopProducts(java.util.Map<String, Object> b) { return degrade("shopProducts"); }
            @Override public String cartDetail() { return degrade("cartDetail"); }
            @Override public String addToCart(com.fasterxml.jackson.databind.JsonNode b) { return degrade("addToCart"); }
            @Override public String removeFromCart(java.util.Map<String, Object> b) { return degrade("removeFromCart"); }
            @Override public String orderData() { return degrade("orderData"); }
            @Override public String orderItemDetail() { return degrade("orderItemDetail"); }
            @Override public String createOrder(com.fasterxml.jackson.databind.JsonNode b) { return degrade("createOrder"); }
            @Override public String createOrderItems(com.fasterxml.jackson.databind.JsonNode b) { return degrade("createOrderItems"); }
            @Override public String redeemCoupon(com.fasterxml.jackson.databind.JsonNode b) { return degrade("redeemCoupon"); }
            @Override public String couponList() { return degrade("couponList"); }
            @Override public String couponUserList() { return degrade("couponUserList"); }
            @Override public String userData() { return degrade("userData"); }
            @Override public String addressList() { return degrade("addressList"); }
            @Override public String noteList() { return degrade("noteList"); }
            @Override public String momentList() { return degrade("momentList"); }
            @Override public String searchProductRaw() { return degrade("searchProduct"); }
        };
    }
}
