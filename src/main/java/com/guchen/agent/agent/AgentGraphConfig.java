package com.guchen.agent.agent;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LangGraph4j DAG 编排 —— 用于「复杂茶礼推荐」这类多步任务。
 *
 * 图结构（DAG）：
 *
 *                    START
 *                      │
 *              ┌───────┴───────┐
 *              ▼               ▼
 *        [分析需求]        [检索茶知识]        ← 两个分支并行
 *              │               │
 *              └───────┬───────┘
 *                      ▼
 *                 [聚合候选]                  ← 汇聚点（barrier）
 *                      │
 *                 ┌────┴────┐
 *          命中充足？        命中不足？
 *                 │             │
 *                 ▼             ▼
 *           [生成推荐]     [扩大检索范围] ──┐  ← 条件边
 *                 │                        │
 *                 │◄───────────────────────┘
 *                 ▼
 *                END
 *
 * 面试要点：
 *  - 为什么用 DAG 而不是让 LLM 自由发挥：流程可控、可重放、可观测，每个节点独立埋点；
 *  - 条件边实现「检索不足则扩大范围」的重试语义，但有硬上限，不会无限循环；
 *  - 并行分支用 CompletableFuture 扇出，汇聚点等齐 —— 这是「dependsOn」的落地方式。
 */
@Slf4j
@Configuration
public class AgentGraphConfig {

    @Value("${agent.max-iterations:6}")
    private int maxIterations;

    private final ExecutorService parallelPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "graph-parallel");
        t.setDaemon(true);
        return t;
    });

    /** 图节点名常量 */
    public static final String NODE_ANALYZE = "analyze_demand";
    public static final String NODE_RETRIEVE = "retrieve_knowledge";
    public static final String NODE_AGGREGATE = "aggregate_candidates";
    public static final String NODE_EXPAND = "expand_retrieval";
    public static final String NODE_RECOMMEND = "generate_recommendation";

    @Bean
    public CompiledGraph<AgentState> teaRecommendationGraph() throws Exception {

        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);

        // ---------- 节点 1：分析用户需求（可并行分支 A） ----------
        graph.addNode(NODE_ANALYZE, node_async(state -> {
            String query = (String) state.value("query").orElse("");
            log.debug("[graph] 分析需求: {}", query);
            Map<String, Object> out = new HashMap<>();
            out.put("parsed_intent", parseIntent(query));
            return out;
        }));

        // ---------- 节点 2：检索茶知识（可并行分支 B） ----------
        graph.addNode(NODE_RETRIEVE, node_async(state -> {
            String query = (String) state.value("query").orElse("");
            log.debug("[graph] 检索知识: {}", query);
            Map<String, Object> out = new HashMap<>();
            // 真实项目里这里调 HybridRetriever（BM25 + 向量 + RRF + Rerank）
            out.put("knowledge_hits", retrieveStub(query));
            out.put("round", state.value("round").orElse(1));
            return out;
        }));

        // ---------- 节点 3：汇聚候选（barrier） ----------
        graph.addNode(NODE_AGGREGATE, node_async(state -> {
            int hits = (Integer) state.value("knowledge_hits").orElse(0);
            log.debug("[graph] 汇聚候选，命中 {} 条", hits);
            Map<String, Object> out = new HashMap<>();
            out.put("sufficient", hits >= 3);
            return out;
        }));

        // ---------- 节点 4：扩大检索范围（条件分支） ----------
        graph.addNode(NODE_EXPAND, node_async(state -> {
            int round = (Integer) state.value("round").orElse(1);
            log.debug("[graph] 命中不足，扩大检索范围，第 {} 轮", round + 1);
            Map<String, Object> out = new HashMap<>();
            out.put("round", round + 1);
            out.put("knowledge_hits", retrieveStub("expanded"));
            return out;
        }));

        // ---------- 节点 5：生成推荐（收口） ----------
        graph.addNode(NODE_RECOMMEND, node_async(state -> {
            log.debug("[graph] 生成最终推荐");
            Map<String, Object> out = new HashMap<>();
            out.put("recommendation", "（此处由 LlmClient 基于 knowledge_hits 生成最终文案）");
            return out;
        }));

        // ---------- 边：START 扇出到两个并行分支 ----------
        graph.addEdge(START, NODE_ANALYZE);
        graph.addEdge(START, NODE_RETRIEVE);

        // ---------- 边：两个分支汇聚 ----------
        graph.addEdge(NODE_ANALYZE, NODE_AGGREGATE);
        graph.addEdge(NODE_RETRIEVE, NODE_AGGREGATE);

        // ---------- 条件边：命中是否充足 ----------
        graph.addConditionalEdges(NODE_AGGREGATE,
                state -> {
                    boolean sufficient = (Boolean) state.value("sufficient").orElse(false);
                    int round = (Integer) state.value("round").orElse(1);
                    // 命中充足 或 已达重试上限 → 生成推荐；否则扩大检索
                    return CompletableFuture.completedFuture(
                            (sufficient || round >= 2) ? NODE_RECOMMEND : NODE_EXPAND);
                },
                Map.of(NODE_RECOMMEND, NODE_RECOMMEND, NODE_EXPAND, NODE_EXPAND));

        // ---------- 扩大检索后回到汇聚（形成受控回边） ----------
        graph.addEdge(NODE_EXPAND, NODE_AGGREGATE);
        graph.addEdge(NODE_RECOMMEND, END);

        return graph.compile();
    }

    // ==================== 桩实现（替换为真实逻辑） ====================

    private String parseIntent(String query) {
        if (query.contains("送礼") || query.contains("礼盒")) return "gift";
        if (query.contains("自饮") || query.contains("自己喝")) return "self";
        return "general";
    }

    /**
     * 检索桩：真实实现见 HybridRetriever。
     * 这里返回命中数量，供条件边判断。
     */
    private int retrieveStub(String query) {
        // TODO 接入 HybridRetriever：BM25(MySQL FULLTEXT) + 向量(Qdrant) → RRF → Rerank
        return "expanded".equals(query) ? 5 : 2;
    }
}
