package com.guchen.agent.rag;

import com.guchen.agent.rag.HybridRetriever.RetrievalMode;
import com.guchen.agent.rag.HybridRetriever.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * RAG 检索评测：在同一批评测集上对比 BM25-only / Vector-only / Hybrid 三路的 Recall@5。
 *
 * 为什么必须做对照（面试会追问）：
 *   「我用了混合检索」只是实现陈述；「混合检索把 Recall@5 从 X% 提到 Y%」才是工程结论。
 *   没有对照数字，就无法回答「为什么不直接用向量」「RRF 到底有没有用」。
 *
 * 评测口径：
 *   - 评测集来自 db_agent.eval_case，每条含 question 与 expected_chunk_ids（逗号分隔的期望 chunk id）
 *   - 对每个问题分别用三种模式检索 topK=5，判断期望 chunk 是否出现在结果中
 *   - Recall@5 = 命中数 / 总用例数
 *   - 结果逐条写入 eval_run（run_name 区分三路），汇总写入 vector_benchmark
 *
 * 注意：Recall 的严格定义是「召回的期望条目数 / 期望条目总数」。
 * 本实现按「至少命中一个期望 chunk 即算该用例命中」计算（hit-rate 口径），
 * 这是检索评测常用且更贴近业务的口径（用户只要拿到一条正确依据即可回答），
 * 故报告中统一称 Recall@5 并在 note 里注明口径。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/eval")
@RequiredArgsConstructor
public class RetrievalEvalController {

    private final HybridRetriever retriever;
    private final JdbcTemplate jdbcTemplate;

    /** 四种模式的展示名，同时作为 eval_run.run_name */
    private static final Map<RetrievalMode, String> RUN_NAMES = new LinkedHashMap<>() {{
        put(RetrievalMode.BM25_ONLY, "bm25_only");
        put(RetrievalMode.VECTOR_ONLY, "vector_only");
        put(RetrievalMode.HYBRID_RRF_ONLY, "hybrid_rrf_only");
        put(RetrievalMode.HYBRID, "hybrid");
    }};

    /**
     * 跑一次完整三路对照。
     *
     * @param topK    评估截断位置，默认 5（即 Recall@5）
     * @param persist 是否把结果写入 eval_run / vector_benchmark
     */
    @PostMapping("/recall")
    public Map<String, Object> runRecall(
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "true") boolean persist) {

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> cases = jdbcTemplate.queryForList(
                "SELECT id, question, expected_chunk_ids FROM eval_case ORDER BY id");
        if (cases.isEmpty()) {
            return Map.of("error", "eval_case 为空，请先导入评测集");
        }

        // 每个模式：命中数 / 逐条明细
        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> details = new LinkedHashMap<>();
        for (String name : RUN_NAMES.values()) {
            hits.put(name, 0);
            details.put(name, new ArrayList<>());
        }

        for (Map<String, Object> c : cases) {
            long caseId = ((Number) c.get("id")).longValue();
            String question = String.valueOf(c.get("question"));
            Set<String> expected = parseIds(c.get("expected_chunk_ids"));

            for (Map.Entry<RetrievalMode, String> e : RUN_NAMES.entrySet()) {
                String runName = e.getValue();
                long s = System.currentTimeMillis();
                List<RetrievedChunk> got;
                try {
                    got = retriever.retrieve(question, topK, e.getKey());
                } catch (Exception ex) {
                    log.warn("评测检索失败 mode={} q={}: {}", runName, question, ex.getMessage());
                    got = List.of();
                }
                int latency = (int) (System.currentTimeMillis() - s);

                List<String> gotIds = got.stream().map(RetrievedChunk::id).toList();
                boolean hit = gotIds.stream().anyMatch(expected::contains);
                if (hit) hits.merge(runName, 1, Integer::sum);

                Map<String, Object> d = new LinkedHashMap<>();
                d.put("caseId", caseId);
                d.put("question", question);
                d.put("expected", expected);
                d.put("got", gotIds);
                d.put("hit", hit);
                d.put("latencyMs", latency);
                details.get(runName).add(d);

                if (persist) {
                    jdbcTemplate.update(
                            "INSERT INTO eval_run (run_name, config, case_id, hit_at_5, latency_ms, recall) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)",
                            runName,
                            "{\"topK\":" + topK + ",\"mode\":\"" + e.getKey() + "\"}",
                            caseId, hit ? 1 : 0, latency, hit ? 1.0 : 0.0);
                }
            }
        }

        int total = cases.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCases", total);
        summary.put("topK", topK);
        summary.put("persisted", persist);

        Map<String, Object> recalls = new LinkedHashMap<>();
        Map<String, Double> recallPct = new LinkedHashMap<>();
        for (String name : RUN_NAMES.values()) {
            double r = total == 0 ? 0 : (double) hits.get(name) / total;
            recalls.put(name, Map.of("hit", hits.get(name), "recall", round4(r)));
            recallPct.put(name, round4(r * 100));
        }
        summary.put("recallAtK", recalls);

        // 关键结论：拆开看每一阶段的贡献（写简历用的就是这些数）
        double bm25 = recallPct.get("bm25_only");
        double vec = recallPct.get("vector_only");
        double rrf = recallPct.get("hybrid_rrf_only");
        double hyb = recallPct.get("hybrid");
        summary.put("lift", Map.of(
                "hybrid_vs_bm25_pp", round2(hyb - bm25),
                "hybrid_vs_vector_pp", round2(hyb - vec),
                "rrf_vs_bm25_pp", round2(rrf - bm25),
                "rerank_vs_rrf_pp", round2(hyb - rrf)
        ));

        if (persist) {
            // 写入 vector_benchmark（表语义为「向量库选型对比」，这里扩展为检索策略对比）
            for (String name : RUN_NAMES.values()) {
                jdbcTemplate.update(
                        "INSERT INTO vector_benchmark (backend, index_type, dimension, point_count, top_k, "
                                + "recall_at_k, note) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        name,
                        "retrieval-strategy",
                        1024,
                        countChunks(),
                        topK,
                        (double) hits.get(name) / total,
                        "RAG 检索策略对照（hit-rate 口径），评测集 " + total + " 条");
            }
        }

        summary.put("costMs", System.currentTimeMillis() - t0);
        summary.put("detail", details);
        log.info("RAG 评测完成: {}", recalls);
        return summary;
    }

    /** 逐条明细查询（便于人工核查哪些问题没命中）。 */
    @GetMapping("/cases")
    public List<Map<String, Object>> listCases() {
        return jdbcTemplate.queryForList(
                "SELECT id, question, expected_chunk_ids, category, difficulty FROM eval_case ORDER BY id");
    }

    /** 查看历史评测结果汇总。 */
    @GetMapping("/runs")
    public List<Map<String, Object>> listRuns() {
        return jdbcTemplate.queryForList(
                "SELECT run_name, COUNT(*) AS cases, SUM(hit_at_5) AS hits, "
                        + "ROUND(AVG(hit_at_5)*100, 2) AS recall_pct, ROUND(AVG(latency_ms)) AS avg_ms "
                        + "FROM eval_run GROUP BY run_name ORDER BY recall_pct DESC");
    }

    private int countChunks() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_chunk", Integer.class);
        return n == null ? 0 : n;
    }

    private Set<String> parseIds(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String s : String.valueOf(raw).split("[,，\\s]+")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
