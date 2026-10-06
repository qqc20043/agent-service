package com.guchen.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guchen.agent.client.TearSpringClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.security.MessageDigest;

/**
 * 用户上下文解析：把「token」翻译成记忆体系的归属键。
 *
 * 设计（见《漫思茶-Agent任务化升级方案》Phase 3）：
 *  - 登录用户 → 记忆域 "u{userId}"：跨会话、跨设备共享上下文与画像；
 *  - 未登录   → 记忆域 "s{sessionId}"：退化为会话级（匿名访客）。
 *
 * 为什么用 /user/userData 反查而不是本地解析 JWT：
 *  老项目 token 经过 GZIP 压缩 + HS512 签名，本地验签要复制签名密钥与解压逻辑；
 *  反查一次 userData（~10ms）同时完成「验签 + 过期校验 + Redis 双向比对」，
 *  且不需要知道签名密钥——用一次轻量调用换三条安全保证。
 *  结果按 token 摘要缓存 30 分钟，避免每轮重复调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContextService {

    private final TearSpringClient tearSpring;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String KEY_UID_MAP = "ms:agent:uidmap:";

    /** token → userId（Redis 缓存 30 分钟）；未登录/token 失效返回 null。 */
    public Long resolveUserId(String token) {
        if (token == null || token.isBlank()) return null;
        String key = KEY_UID_MAP + sha1(token);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                try {
                    long uid = Long.parseLong(cached);
                    return uid > 0 ? uid : null;
                } catch (NumberFormatException ignored) {
                }
            }
            JsonNode root = mapper.readTree(tearSpring.userData());
            long uid = root.path("userData").path("id").asLong(0);
            if (root.path("code").asInt(0) != 200 || uid <= 0) return null;
            redis.opsForValue().set(key, String.valueOf(uid), Duration.ofMinutes(30));
            return uid;
        } catch (Exception e) {
            log.debug("解析用户身份失败（按匿名处理）: {}", e.getMessage());
            return null;
        }
    }

    /** 记忆域：登录用户按 userId，匿名按 sessionId。 */
    public String memoryScope(String token, String sessionId) {
        Long uid = resolveUserId(token);
        return uid != null ? ("u" + uid) : ("s" + sessionId);
    }

    private String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
