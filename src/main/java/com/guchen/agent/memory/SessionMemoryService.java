package com.guchen.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guchen.agent.llm.LlmClient;
import com.guchen.agent.llm.LlmClient.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆：短期上下文 + 摘要压缩。
 *
 * 复用老项目已有的 StringRedisTemplate 注入范式（见老项目 UserServiceImpl:139 的验证码 TTL 用法），
 * 但这里解决的是新问题：多轮对话上下文会线性增长，必须做窗口 + 摘要。
 *
 * 三层策略：
 *  1. **最近 N 轮原文**：保证指代消解（"它多少钱"能知道"它"指什么）
 *  2. **更早内容摘要**：超过窗口的历史用 LLM 压缩成一段摘要，控制 token
 *  3. **TTL 过期**：会话不活跃 2 小时后自动释放
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMemoryService {

    private final StringRedisTemplate redis;
    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.memory.max-turns:10}")
    private int maxTurns;

    @Value("${agent.memory.ttl-minutes:120}")
    private int ttlMinutes;

    private static final String KEY_HISTORY = "ms:agent:mem:history:";
    private static final String KEY_SUMMARY = "ms:agent:mem:summary:";
    /** 推荐候选（含规格）暂存：两阶段推荐需要跨轮保存——第 1 轮登记，第 2 轮用户选定后取用 */
    private static final String KEY_REC_CANDIDATES = "ms:agent:mem:reccand:";

    /** 暂存本轮推荐的候选商品（含规格），供下一轮「用户选定某款」时取用。 */
    public void saveRecommendCandidates(String scope, String cardsJson) {
        if (scope == null || scope.isBlank() || cardsJson == null) return;
        try {
            redis.opsForValue().set(KEY_REC_CANDIDATES + scope, cardsJson,
                    Duration.ofMinutes(ttlMinutes));
        } catch (Exception e) {
            log.debug("暂存推荐候选失败 scope={}: {}", scope, e.getMessage());
        }
    }

    /** 读取暂存的推荐候选（JSON 数组字符串）；无则返回 null。 */
    public String loadRecommendCandidates(String scope) {
        if (scope == null || scope.isBlank()) return null;
        try {
            return redis.opsForValue().get(KEY_REC_CANDIDATES + scope);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取历史消息（供组装 prompt）。 */
    public List<Message> loadHistory(String sessionId) {
        List<Message> messages = new ArrayList<>();

        // 摘要放在最前面，作为长期记忆
        String summary = redis.opsForValue().get(KEY_SUMMARY + sessionId);
        if (summary != null && !summary.isBlank()) {
            messages.add(Message.system("以下是本次会话更早内容的摘要：\n" + summary));
        }

        String json = redis.opsForValue().get(KEY_HISTORY + sessionId);
        if (json == null || json.isBlank()) return messages;

        try {
            List<Map<String, String>> list = mapper.readValue(json,
                    new TypeReference<List<Map<String, String>>>() {});
            for (Map<String, String> m : list) {
                messages.add(new Message(m.get("role"), m.get("content"), null, null, null));
            }
        } catch (Exception e) {
            log.warn("解析会话历史失败 session={}", sessionId, e);
        }
        return messages;
    }

    /** 追加一轮问答，超窗口则触发摘要压缩。 */
    public void append(String sessionId, String userInput, String assistantOutput) {
        List<Map<String, String>> history = readRaw(sessionId);
        history.add(Map.of("role", "user", "content", userInput));
        history.add(Map.of("role", "assistant", "content", assistantOutput));

        // 超出窗口：把最老的几轮压成摘要
        if (history.size() > maxTurns * 2) {
            int overflow = history.size() - maxTurns * 2;
            List<Map<String, String>> toSummarize = new ArrayList<>(history.subList(0, overflow));
            history = new ArrayList<>(history.subList(overflow, history.size()));
            summarizeAsync(sessionId, toSummarize);
        }

        writeRaw(sessionId, history);
    }

    /**
     * 摘要压缩：把溢出的历史交给 LLM 压成 2-3 句话。
     * 这是「上下文窗口超了怎么办」的标准答案之一。
     */
    private void summarizeAsync(String sessionId, List<Map<String, String>> toSummarize) {
        StringBuilder sb = new StringBuilder();
        toSummarize.forEach(m -> sb.append(m.get("role")).append(": ").append(m.get("content")).append("\n"));

        String oldSummary = redis.opsForValue().get(KEY_SUMMARY + sessionId);
        String prompt = (oldSummary == null ? "" : "已有摘要：\n" + oldSummary + "\n\n")
                + "请把以下对话压缩成 3 句话以内的摘要，保留用户的关键偏好与已确认的事实：\n" + sb;

        try {
            LlmClient.ChatResult r = llm.chat(
                    List.of(Message.system("你是对话摘要助手，只输出摘要正文。"),
                            Message.user(prompt)),
                    null);
            redis.opsForValue().set(KEY_SUMMARY + sessionId, r.content(),
                    Duration.ofMinutes(ttlMinutes));
            log.debug("会话摘要已更新 session={}", sessionId);
        } catch (Exception e) {
            log.warn("摘要生成失败，降级为直接截断 session={}", sessionId, e);
        }
    }

    public void clear(String sessionId) {
        redis.delete(List.of(KEY_HISTORY + sessionId, KEY_SUMMARY + sessionId));
        log.info("会话已清空 session={}", sessionId);
    }

    // ==================== 底层读写 ====================

    private List<Map<String, String>> readRaw(String sessionId) {
        String json = redis.opsForValue().get(KEY_HISTORY + sessionId);
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void writeRaw(String sessionId, List<Map<String, String>> history) {
        try {
            redis.opsForValue().set(KEY_HISTORY + sessionId,
                    mapper.writeValueAsString(history),
                    Duration.ofMinutes(ttlMinutes));
        } catch (Exception e) {
            log.error("写入会话历史失败 session={}", sessionId, e);
        }
    }
}
