package com.guchen.agent.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 声明式调用老项目 tearSpring 的接口（SpringCloud OpenFeign + LoadBalancer）。
 *
 * 老项目只需在 application.yaml 里加：
 *   spring.application.name=tearSpring
 *   spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848
 * 即可被发现，无需改任何业务代码。
 *
 * 注意：老项目是 Spring Boot 2.6.13，返回体是 com.guchen.utils.Result，
 * 这里统一用 String/Map 接收，避免跨版本反序列化耦合。
 */
@FeignClient(
        name = "${services.tear-spring.service-name}",
        path = "",
        configuration = FeignAuthConfig.class,
        fallbackFactory = TearSpringClientFallback.class
)
public interface TearSpringClient {

    // ==================== 商品 ====================

    @PostMapping("/product/selectSingle")
    String productDetail(@RequestBody Map<String, Object> body);

    @GetMapping("/product/shopSpecList")
    String shopSpecList();

    @GetMapping("/product/teaware")
    String teawareList();

    // ==================== 主题 / 分类 ====================

    @GetMapping("/theme/lists")
    String themeList();

    @GetMapping("/theme/themeProductList")
    String themeProductList();

    @GetMapping("/category/lists")
    String categoryList();

    // ==================== 店铺 ====================

    @PostMapping("/shop/SingleOne")
    String shopDetail(@RequestBody Map<String, Object> body);

    @PostMapping("/shop/productList")
    String shopProducts(@RequestBody Map<String, Object> body);

    // ==================== 购物车 ====================

    @GetMapping("/cart/VCartDetail")
    String cartDetail();

    @PostMapping("/cart/insertCartItem")
    String addToCart(@RequestBody com.fasterxml.jackson.databind.JsonNode body);

    @DeleteMapping("/cart/delCartItem")
    String removeFromCart(@RequestBody Map<String, Object> body);

    // ==================== 订单 ====================

    @GetMapping("/order/VOrderData")
    String orderData();

    @GetMapping("/order/VOrderItemDetail")
    String orderItemDetail();

    @PostMapping("/order/OrderData")
    String createOrder(@RequestBody com.fasterxml.jackson.databind.JsonNode body);

    @PostMapping("/order/OrderItemData")
    String createOrderItems(@RequestBody com.fasterxml.jackson.databind.JsonNode body);

    // ==================== 优惠券 ====================

    @PostMapping("/coupon/codeNo")
    String redeemCoupon(@RequestBody com.fasterxml.jackson.databind.JsonNode body);

    @GetMapping("/coupon/selectCUListId")
    String couponList();

    @GetMapping("/coupon/selectCouponUserVOList")
    String couponUserList();

    // ==================== 用户 ====================

    @GetMapping("/user/userData")
    String userData();

    @GetMapping("/user/addressI")
    String addressList();

    @GetMapping("/user/noteI")
    String noteList();

    // ==================== 茶友圈 ====================

    @GetMapping("/moment/list")
    String momentList();

    /**
     * 关键词搜索：老项目没有专门的搜索接口，
     * 这里先用全量商品列表 + 本地过滤兜底；后续可在老项目加 /product/search。
     */
    @GetMapping("/product/shopSpecList")
    String searchProductRaw();

    default String searchProduct(String keyword) {
        return searchProductRaw();
    }
}
