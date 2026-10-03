package com.guchen.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LangGraph4j DAG 联调探针：直接驱动「茶礼推荐」状态图。
 *
 * 图结构：START 扇出 → [分析需求 ∥ 检索知识] → 汇聚（barrier）→
 *         条件边（命中充足→生成推荐 / 不足→扩大检索回边，受控重试）→ END
 *
 * W2 验收 + 面试演示用：现场展示「并行分支 + barrier + 条件边回边」真实执行。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/graph-probe")
public class GraphProbeController {

    private final CompiledGraph<AgentState> graph;

    public GraphProbeController(CompiledGraph<AgentState> teaRecommendationGraph) {
        this.graph = teaRecommendationGraph;
    }

    /**
     * curl 验证：
     *   curl "http://localhost:8081/api/agent/graph-probe?query=送礼的绿茶礼盒"
     */
    @GetMapping
    public Map<String, Object> run(@RequestParam(defaultValue = "推荐一款送礼的绿茶") String query) throws Exception {
        long t0 = System.currentTimeMillis();
        log.info("[graph-probe] 驱动 DAG，query={}", query);

        AgentState finalState = graph.invoke(Map.of("query", query))
                .orElseThrow(() -> new IllegalStateException("图执行未返回终态"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("parsed_intent", finalState.<String>value("parsed_intent").orElse(null));
        out.put("knowledge_hits", finalState.<Integer>value("knowledge_hits").orElse(0));
        out.put("round", finalState.<Integer>value("round").orElse(1));
        out.put("sufficient", finalState.<Boolean>value("sufficient").orElse(false));
        out.put("recommendation", finalState.<String>value("recommendation").orElse(null));
        out.put("costMs", System.currentTimeMillis() - t0);
        return out;
    }
}
