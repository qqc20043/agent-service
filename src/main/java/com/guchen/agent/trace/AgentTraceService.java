package com.guchen.agent.trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Agent 链路追踪与成本计量。
 *
 * 设计要点：**复用老项目 event_log 表的思路**（老项目 db_tear 里已有
 * event_name / executed_at / affected_rows 的定时任务审计表），
 * 这里把同一套「事件溯源」范式扩展成 Agent 专用：
 *   - agent_trace      ：一次对话一条，记录 token 与耗时
 *   - agent_tool_call  ：每次工具调用一条，写操作带幂等键
 *
 * 写库走 @Async，不能阻塞 SSE 主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private final JdbcTemplate jdbcTemplate;

    @Async
    public void recordTrace(String traceId, String sessionId, String spanName,
                            int promptTokens, int completionTokens, int latencyMs) {
        try {
            double cost = estimateCost(promptTokens, completionTokens);
            jdbcTemplate.update("""
                    INSERT INTO agent_trace
                      (trace_id, session_id, span_name, prompt_tokens, completion_tokens, cost_yuan, latency_ms, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                    """, traceId, sessionId, spanName, promptTokens, completionTokens, cost, latencyMs);
        } catch (Exception e) {
            log.warn("trace 落库失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 工具调用记录。
     *
     * 幂等设计：写操作的 idempotent_key = traceId + toolName，
     * 表上建 UNIQUE KEY，重复插入直接忽略 —— 这就是「Agent 重试导致重复下单」的防线。
     * 与老项目 exchange_coupon 存储过程里的 SELECT ... FOR UPDATE 是同一问题的两种解法：
     *   - 老项目：数据库行锁（悲观）
     *   - Agent 侧：唯一键去重（乐观 + 幂等键）
     * 面试时能把这个对比讲清楚，比背八股强得多。
     */
    @Async
    public void recordToolCall(String traceId, String toolName, String args,
                               int latencyMs, boolean writeOperation) {
        try {
            String idempotentKey = writeOperation
                    ? traceId + ":" + toolName
                    : null;

            jdbcTemplate.update("""
                    INSERT IGNORE INTO agent_tool_call
                      (trace_id, tool_name, request_body, latency_ms, idempotent_key, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                    """, traceId, toolName, args, latencyMs, idempotentKey);
        } catch (Exception e) {
            log.warn("工具调用落库失败: {}", e.getMessage());
        }
    }

    /**
     * 成本估算。简历上写成本数字必须来自这里聚合的真实 token 数：
     *   SELECT SUM(cost_yuan), AVG(prompt_tokens+completion_tokens) FROM agent_trace;
     */
    private double estimateCost(int promptTokens, int completionTokens) {
        double in = promptTokens / 1_000_000.0 * 1.0;
        double out = completionTokens / 1_000_000.0 * 2.0;
        return Math.round((in + out) * 1_000_000) / 1_000_000.0;
    }
}
