package com.guchen.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置 —— 支撑「网页端 + 小程序端」双端接入。
 *
 * <p>为什么必须显式配：agent-service 的接口会被三种来源调用
 * <ol>
 *   <li><b>网页端 chat.html</b>：与 agent-service 同源（都托管在 8081），理论上不需要 CORS；
 *       但 H5 版商城独立部署后（Nginx 静态站），就会变成跨域调用 → 需要放行。</li>
 *   <li><b>微信小程序</b>：小程序 <b>不走浏览器的 CORS 机制</b>（它由微信客户端发请求，
 *       校验的是「合法域名」白名单，不是 Access-Control-Allow-Origin）。
 *       所以本配置对小程序的正式环境不是必需，但开发者工具勾选「不校验合法域名」时
 *       仍是浏览器内核在发请求，放行可避免本地联调踩坑。</li>
 *   <li><b>本地调试</b>：file:// 打开页面、或用 Swagger/Postman 等工具时。</li>
 * </ol>
 *
 * <p><b>安全考量</b>：这里用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins("*")}，
 * 因为开启 {@code allowCredentials} 后，规范明确禁止 {@code *}（会抛异常或静默失效）。
 * 生产环境应把 {@code agent.cors.allowed-origins} 收窄为真实域名白名单
 * （见《需求汇总》§6.3 安全红线：公网 IP 会被全网扫描）。
 */
@Slf4j
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许的来源。默认覆盖本地开发常用入口；
     * 生产用环境变量覆盖，例如 agent.cors.allowed-origins=https://your-domain.com
     */
    @Value("${agent.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("CORS 放行来源: {}", allowedOrigins);
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // SSE 场景不需要 Cookie 凭据（token 走请求头透传），保持 false 更安全
                .allowCredentials(false)
                .maxAge(1800);
    }
}
