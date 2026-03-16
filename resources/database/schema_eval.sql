-- ============================================================
-- RAG 评测系统表结构
-- ============================================================

-- 评测数据集
CREATE TABLE IF NOT EXISTS `t_eval_dataset`
(
    `id`          bigint(20)   NOT NULL COMMENT '主键ID',
    `name`        varchar(128) NOT NULL COMMENT '数据集名称',
    `description` varchar(512) DEFAULT NULL COMMENT '描述',
    `case_count`  int(11)      DEFAULT 0 COMMENT '用例数量',
    `created_by`  varchar(64)  DEFAULT '' COMMENT '创建人',
    `updated_by`  varchar(64)  DEFAULT '' COMMENT '更新人',
    `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint(1)   DEFAULT 0 COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评测数据集';

-- 评测用例
CREATE TABLE IF NOT EXISTS `t_eval_dataset_case`
(
    `id`                 bigint(20)   NOT NULL COMMENT '主键ID',
    `dataset_id`         bigint(20)   NOT NULL COMMENT '数据集ID',
    `query`              text         NOT NULL COMMENT '用户问题',
    `expected_answer`    text         DEFAULT NULL COMMENT '期望答案',
    `relevant_chunk_ids` json         DEFAULT NULL COMMENT '相关chunk ID列表',
    `intent`             varchar(128) DEFAULT NULL COMMENT '意图标签',
    `created_by`         varchar(64)  DEFAULT '' COMMENT '创建人',
    `updated_by`         varchar(64)  DEFAULT '' COMMENT '更新人',
    `create_time`        datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            tinyint(1)   DEFAULT 0 COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY `idx_dataset_id` (`dataset_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评测用例';

-- 评测运行
CREATE TABLE IF NOT EXISTS `t_eval_run`
(
    `id`              bigint(20)    NOT NULL COMMENT '主键ID',
    `dataset_id`      bigint(20)    NOT NULL COMMENT '数据集ID',
    `status`          varchar(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/COMPLETED/FAILED',
    `total_cases`     int(11)       DEFAULT 0 COMMENT '总用例数',
    `completed_cases` int(11)       DEFAULT 0 COMMENT '已完成用例数',
    `avg_hit_rate`    decimal(5, 4) DEFAULT NULL COMMENT '平均命中率',
    `avg_mrr`         decimal(5, 4) DEFAULT NULL COMMENT '平均MRR',
    `avg_recall`      decimal(5, 4) DEFAULT NULL COMMENT '平均召回率',
    `avg_precision`   decimal(5, 4) DEFAULT NULL COMMENT '平均精确率',
    `avg_faithfulness` decimal(3, 2) DEFAULT NULL COMMENT '平均忠实度(1-5)',
    `avg_relevancy`   decimal(3, 2) DEFAULT NULL COMMENT '平均相关性(1-5)',
    `avg_correctness` decimal(3, 2) DEFAULT NULL COMMENT '平均正确率(1-5)',
    `bad_case_count`  int(11)       DEFAULT 0 COMMENT 'Bad Case数量',
    `error_message`   text          DEFAULT NULL COMMENT '错误信息',
    `started_at`      datetime      DEFAULT NULL COMMENT '开始时间',
    `finished_at`     datetime      DEFAULT NULL COMMENT '完成时间',
    `create_time`     datetime      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     datetime      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset_id` (`dataset_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评测运行记录';

-- 评测运行结果（每条用例）
CREATE TABLE IF NOT EXISTS `t_eval_run_result`
(
    `id`                  bigint(20) NOT NULL COMMENT '主键ID',
    `run_id`              bigint(20) NOT NULL COMMENT '运行ID',
    `case_id`             bigint(20) NOT NULL COMMENT '用例ID',
    `hit_rate`            decimal(5, 4) DEFAULT NULL COMMENT '命中率(0/1)',
    `mrr`                 decimal(5, 4) DEFAULT NULL COMMENT 'MRR',
    `recall_score`        decimal(5, 4) DEFAULT NULL COMMENT '召回率',
    `precision_score`     decimal(5, 4) DEFAULT NULL COMMENT '精确率',
    `retrieved_chunk_ids` json          DEFAULT NULL COMMENT '实际检索到的chunk ID列表',
    `generated_answer`    text          DEFAULT NULL COMMENT '系统生成的回答',
    `faithfulness_score`  decimal(3, 2) DEFAULT NULL COMMENT '忠实度评分(1-5)',
    `faithfulness_reason` text          DEFAULT NULL COMMENT '忠实度评分理由',
    `relevancy_score`     decimal(3, 2) DEFAULT NULL COMMENT '相关性评分(1-5)',
    `relevancy_reason`    text          DEFAULT NULL COMMENT '相关性评分理由',
    `correctness_score`   decimal(3, 2) DEFAULT NULL COMMENT '正确率评分(1-5)',
    `correctness_reason`  text          DEFAULT NULL COMMENT '正确率评分理由',
    `is_fallback`         tinyint(1)    DEFAULT 0 COMMENT '是否兜底回答',
    `is_bad_case`         tinyint(1)    DEFAULT 0 COMMENT '是否Bad Case',
    `root_cause`          varchar(32)   DEFAULT NULL COMMENT '归因：RETRIEVAL/GENERATION/KNOWLEDGE_GAP',
    `latency_ms`          bigint(20)    DEFAULT NULL COMMENT '执行耗时(毫秒)',
    `create_time`         datetime      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_run_id` (`run_id`),
    KEY `idx_case_id` (`case_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评测运行结果';

-- 全局提示词管理表
CREATE TABLE IF NOT EXISTS `t_prompt_template`
(
    `id`          bigint(20)   NOT NULL COMMENT '主键ID',
    `prompt_key`  varchar(128) NOT NULL COMMENT '唯一标识',
    `name`        varchar(128) NOT NULL COMMENT '显示名称',
    `category`    varchar(32)  NOT NULL COMMENT '分类: SYSTEM/SCENE/FLOW/EVAL',
    `content`     longtext     NOT NULL COMMENT '提示词内容',
    `file_path`   varchar(256) DEFAULT NULL COMMENT '原始.st文件路径',
    `variables`   json         DEFAULT NULL COMMENT '变量说明',
    `description` varchar(512) DEFAULT NULL COMMENT '用途说明',
    `version`     int(11)      DEFAULT 1 COMMENT '版本号',
    `enabled`     tinyint(1)   DEFAULT 0 COMMENT '是否启用DB版本',
    `updated_by`  varchar(64)  DEFAULT NULL COMMENT '更新人',
    `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint(1)   DEFAULT 0 COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_key` (`prompt_key`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='全局提示词管理';
