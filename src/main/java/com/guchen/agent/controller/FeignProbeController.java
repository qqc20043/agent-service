package com.guchen.agent.controller;

import com.guchen.agent.client.TearSpringClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenFeign 联调探针：验证「agent-service ——Feign/Nacos服务发现——> tearSpring」链路。
 *
 * 三个端点分别命中老项目的分类 / 主题 / 商品规格接口，
 * token 由调用方携带、经 FeignAuthConfig 原样透传（老项目拦截器要求 JWT + Redis 双向校验）。
 * W1 验收 + 面试演示用：现场证明两个服务确实在通过注册中心协作。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/feign-probe")
public class FeignProbeController {

    private final TearSpringClient tearSpringClient;

    public FeignProbeController(TearSpringClient tearSpringClient) {
        this.tearSpringClient = tearSpringClient;
    }

    /** 老项目 GET /category/lists —— 商品分类 */
    @GetMapping("/category")
    public String category(@RequestHeader(value = "token", required = false) String token) {
        log.info("[feign-probe] 调用 tearSpring /category/lists");
        return tearSpringClient.categoryList();
    }

    /** 老项目 GET /theme/lists —— 主题列表 */
    @GetMapping("/theme")
    public String theme(@RequestHeader(value = "token", required = false) String token) {
        log.info("[feign-probe] 调用 tearSpring /theme/lists");
        return tearSpringClient.themeList();
    }

    /** 老项目 GET /product/shopSpecList —— 店铺商品规格（全量商品） */
    @GetMapping("/product-specs")
    public String productSpecs(@RequestHeader(value = "token", required = false) String token) {
        log.info("[feign-probe] 调用 tearSpring /product/shopSpecList");
        return tearSpringClient.shopSpecList();
    }
}
