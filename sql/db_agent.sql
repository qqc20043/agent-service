-- ============================================================
-- 漫思茶 Agent 服务数据库（db_agent）
-- 与老项目 db_tear 完全隔离，避免污染已验证的 29 表 / 13 视图
-- ============================================================

CREATE DATABASE IF NOT EXISTS `db_agent`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `db_agent`;

-- ------------------------------------------------------------
-- 1. 知识库文档表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `kb_document`;
CREATE TABLE `kb_document` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `title`         varchar(255) NOT NULL COMMENT '文档标题',
  `source_type`   varchar(20)  NOT NULL COMMENT '来源类型 xlsx/docx/pdf/image/manual',
  `source_path`   varchar(500) DEFAULT NULL COMMENT '原始文件路径',
  `chunk_count`   int          DEFAULT 0 COMMENT '切分出的知识块数',
  `status`        varchar(20)  DEFAULT 'pending' COMMENT 'pending/parsed/embedded/failed',
  `error_msg`     varchar(500) DEFAULT NULL,
  `created_at`    datetime     DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_status` (`status`),
  INDEX `idx_source_type` (`source_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档表';

-- ------------------------------------------------------------
-- 2. 知识块表（BM25 检索依赖 ngram FULLTEXT）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `kb_chunk`;
CREATE TABLE `kb_chunk` (
  `id`           bigint      NOT NULL AUTO_INCREMENT,
  `document_id`  bigint      NOT NULL,
  `parent_id`    bigint      DEFAULT NULL COMMENT '父块，实现父子块检索',
  `content`      text        NOT NULL,
  `token_count`  int         DEFAULT 0,
  `vector_id`    varchar(64) DEFAULT NULL COMMENT 'Qdrant/Milvus 中的点 ID',
  `chunk_index`  int         DEFAULT 0 COMMENT '在原文档中的顺序',
  `metadata`     json        DEFAULT NULL COMMENT '茶类/主题等过滤字段',
  `created_at`   datetime    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_doc` (`document_id`),
  INDEX `idx_parent` (`parent_id`),
  FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram,
  CONSTRAINT `fk_chunk_doc` FOREIGN KEY (`document_id`)
    REFERENCES `kb_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识块表';

-- ------------------------------------------------------------
-- 3. Agent 链路追踪表（老项目 event_log 的 Agent 化扩展）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `agent_trace`;
CREATE TABLE `agent_trace` (
  `id`                bigint       NOT NULL AUTO_INCREMENT,
  `trace_id`          varchar(64)  NOT NULL COMMENT '一次请求的全局追踪 ID',
  `session_id`        varchar(64)  DEFAULT NULL COMMENT '会话 ID',
  `span_name`         varchar(64)  NOT NULL COMMENT '阶段名 agent.run/rag.retrieve/llm.chat',
  `intent`            varchar(64)  DEFAULT NULL COMMENT '路由出的意图',
  `prompt_tokens`     int          DEFAULT 0,
  `completion_tokens` int          DEFAULT 0,
  `cost_yuan`         decimal(10,6) DEFAULT 0 COMMENT '本次成本（元）',
  `latency_ms`        int          DEFAULT NULL,
  `created_at`        datetime     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_trace` (`trace_id`),
  INDEX `idx_session` (`session_id`),
  INDEX `idx_span` (`span_name`),
  INDEX `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 链路追踪表';

-- ------------------------------------------------------------
-- 4. 工具调用表（幂等键唯一约束是关键）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `agent_tool_call`;
CREATE TABLE `agent_tool_call` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `trace_id`       varchar(64) NOT NULL,
  `tool_name`      varchar(64) NOT NULL,
  `request_body`   json        DEFAULT NULL,
  `response_code`  int         DEFAULT NULL,
  `latency_ms`     int         DEFAULT NULL,
  `idempotent_key` varchar(128) DEFAULT NULL COMMENT '写操作幂等键 traceId:toolName',
  `created_at`     datetime    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),
  INDEX `idx_trace` (`trace_id`),
  INDEX `idx_tool` (`tool_name`),
  INDEX `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 工具调用审计表';

-- ------------------------------------------------------------
-- 5. RAG 评估集（简历里那份评估报告的底层数据）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `eval_case`;
CREATE TABLE `eval_case` (
  `id`                 bigint      NOT NULL AUTO_INCREMENT,
  `question`           text        NOT NULL COMMENT '测试问题',
  `ground_truth`       text        NOT NULL COMMENT '标准答案',
  `expected_chunk_ids` varchar(500) DEFAULT NULL COMMENT '期望命中的 chunk',
  `category`           varchar(32) DEFAULT NULL COMMENT '分类：茶知识/商品/多轮指代',
  `difficulty`         varchar(16) DEFAULT 'normal',
  `created_at`         datetime    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 评估集';

-- ------------------------------------------------------------
-- 6. 评估运行结果（支撑「纯向量 vs +混合 vs +Rerank」三组对比）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `eval_run`;
CREATE TABLE `eval_run` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `run_name`       varchar(64) NOT NULL COMMENT '实验名：vector_only / hybrid / hybrid_rerank',
  `config`         json        DEFAULT NULL COMMENT '本次实验的配置快照',
  `case_id`        bigint      NOT NULL,
  `hit_at_5`       tinyint(1)  DEFAULT 0 COMMENT 'Top-5 是否命中期望 chunk',
  `answer_correct` tinyint(1)  DEFAULT 0 COMMENT '答案是否正确',
  `latency_ms`     int         DEFAULT NULL,
  `recall`         decimal(5,4) DEFAULT NULL,
  `created_at`     datetime    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_run` (`run_name`),
  INDEX `idx_case` (`case_id`),
  CONSTRAINT `fk_eval_case` FOREIGN KEY (`case_id`)
    REFERENCES `eval_case` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 评估运行结果';

-- ------------------------------------------------------------
-- 7. 向量库选型对比结果（Qdrant vs Milvus）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `vector_benchmark`;
CREATE TABLE `vector_benchmark` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `backend`       varchar(16) NOT NULL COMMENT 'qdrant / milvus',
  `index_type`    varchar(32) DEFAULT NULL COMMENT 'HNSW / IVF_FLAT',
  `dimension`     int         DEFAULT NULL,
  `point_count`   int         DEFAULT NULL,
  `top_k`         int         DEFAULT NULL,
  `recall_at_k`   decimal(5,4) DEFAULT NULL,
  `p50_latency_ms` int        DEFAULT NULL,
  `p99_latency_ms` int        DEFAULT NULL,
  `memory_mb`     int         DEFAULT NULL,
  `note`          varchar(255) DEFAULT NULL,
  `created_at`    datetime    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_backend` (`backend`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='向量库选型对比基准';

-- ============================================================
-- 演示数据：插入几条茶知识，便于立刻验证 RAG 链路
-- ============================================================
INSERT INTO `kb_document` (`title`, `source_type`, `status`) VALUES
  ('茶叶基础知识手册', 'xlsx', 'embedded'),
  ('茶具与冲泡工艺', 'docx', 'embedded');

INSERT INTO `kb_chunk` (`document_id`, `content`, `token_count`, `chunk_index`) VALUES
  (1, '明前龙井指清明节前采摘的龙井茶，芽叶细嫩，氨基酸含量高，滋味鲜爽回甘，产量稀少因而价格较高。', 45, 0),
  (1, '雨前龙井指清明后谷雨前采摘的龙井茶，叶片稍大，茶多酚含量略高，滋味较明前更浓，价格相对亲民。', 48, 1),
  (1, '碧螺春产于江苏苏州太湖洞庭山，条索纤细卷曲如螺，白毫显露，冲泡后清香持久，有"一嫩三鲜"之说。', 47, 2),
  (1, '白茶按采摘标准分为白毫银针、白牡丹、贡眉、寿眉四类。白茶工艺极简，仅萎凋与干燥，越陈越香。', 45, 3),
  (2, '冲泡绿茶建议水温 80-85℃，避免沸水烫熟茶叶导致苦涩。玻璃杯冲泡可观赏茶叶舒展，投茶量约 3 克配 150ml 水。', 52, 4),
  (2, '紫砂壶适合冲泡乌龙茶、普洱茶，因其双气孔结构能吸附茶香，一壶一茶避免串味。新壶需开壶去土腥味。', 48, 5);

-- 验证 FULLTEXT 是否生效（应能命中「龙井」相关两条）
-- SELECT id, content FROM kb_chunk WHERE MATCH(content) AGAINST ('明前龙井' IN NATURAL LANGUAGE MODE);

-- 验证评估表结构
INSERT INTO `eval_case` (`question`, `ground_truth`, `category`, `difficulty`) VALUES
  ('明前龙井和雨前龙井有什么区别？', '采摘时间不同：明前为清明前，芽叶细嫩、滋味鲜爽、价高；雨前为清明后谷雨前，叶稍大、滋味更浓、价相对低。', '茶知识', 'normal'),
  ('绿茶应该用多少度的水冲泡？', '建议 80-85℃，避免沸水烫熟茶叶导致苦涩。', '茶知识', 'easy'),
  ('白茶有哪些分类？', '白毫银针、白牡丹、贡眉、寿眉四类。', '茶知识', 'easy');
