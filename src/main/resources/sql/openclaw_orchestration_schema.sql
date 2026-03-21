-- =========================================================
-- OpenClaw 桌面编排（Plan/Phase/Node）建表与索引脚本
-- 适用数据库：PostgreSQL
-- 说明：
-- 1) 已将可枚举字段改为 SMALLINT 存储（配合 Java Enum @EnumValue code）
-- 2) 文本常量说明保留在 COMMENT 中，实际存储整数代码
-- =========================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =========================================================
-- 1) 计划实例表
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_instance (
    plan_id              VARCHAR(64) PRIMARY KEY,
    session_id           VARCHAR(128) NOT NULL,
    user_goal            TEXT NOT NULL,
    constraints_json     JSONB,
    success_criteria     TEXT,
    planning_model       VARCHAR(64),
    plan_version         INTEGER NOT NULL DEFAULT 1,
    status               SMALLINT NOT NULL DEFAULT 0,
    current_loop_index   INTEGER NOT NULL DEFAULT 0,
    final_status         SMALLINT,
    error_message        TEXT,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE plan_instance IS '编排计划实例主表';
COMMENT ON COLUMN plan_instance.plan_id IS '计划ID（可用雪花ID/UUID）';
COMMENT ON COLUMN plan_instance.session_id IS '会话ID（建议使用JWT jti）';
COMMENT ON COLUMN plan_instance.user_goal IS '用户目标原文';
COMMENT ON COLUMN plan_instance.constraints_json IS '约束条件(JSON)';
COMMENT ON COLUMN plan_instance.success_criteria IS '成功标准';
COMMENT ON COLUMN plan_instance.planning_model IS '规划所用模型';
COMMENT ON COLUMN plan_instance.plan_version IS '计划版本号';
COMMENT ON COLUMN plan_instance.status IS '计划状态编码：0-PENDING,1-RUNNING,2-WAITING_USER_APPROVAL,3-SUCCESS,4-FAILED,5-CANCELLED';
COMMENT ON COLUMN plan_instance.current_loop_index IS '当前loop轮次';
COMMENT ON COLUMN plan_instance.final_status IS '最终状态编码：0-SUCCESS,1-FAILED,2-PARTIAL,3-CANCELLED';
COMMENT ON COLUMN plan_instance.error_message IS '失败原因';
COMMENT ON COLUMN plan_instance.started_at IS '计划开始时间';
COMMENT ON COLUMN plan_instance.finished_at IS '计划结束时间';
COMMENT ON COLUMN plan_instance.created_at IS '创建时间';
COMMENT ON COLUMN plan_instance.updated_at IS '更新时间';

-- =========================================================
-- 2) 全局规划蓝图表（BigModel 一次性输出）
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_blueprint (
    id                   BIGSERIAL PRIMARY KEY,
    plan_id              VARCHAR(64) NOT NULL,
    plan_version         INTEGER NOT NULL,
    blueprint_json       JSONB NOT NULL,
    generated_by_model   VARCHAR(64),
    generated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_blueprint_plan
        FOREIGN KEY (plan_id) REFERENCES plan_instance(plan_id) ON DELETE CASCADE,
    CONSTRAINT uk_plan_blueprint_plan_version UNIQUE (plan_id, plan_version)
);

COMMENT ON TABLE plan_blueprint IS '全局规划蓝图（按版本存档）';
COMMENT ON COLUMN plan_blueprint.blueprint_json IS '完整蓝图JSON（phase/node/edge/risk）';
COMMENT ON COLUMN plan_blueprint.generated_by_model IS '生成蓝图的模型标识';
COMMENT ON COLUMN plan_blueprint.generated_at IS '模型生成时间';

-- =========================================================
-- 3) 阶段表
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_phase (
    phase_id             VARCHAR(64) PRIMARY KEY,
    plan_id              VARCHAR(64) NOT NULL,
    phase_order          INTEGER NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    objective            TEXT,
    node_ids             JSONB,
    entry_criteria       TEXT,
    exit_criteria        TEXT,
    status               SMALLINT NOT NULL DEFAULT 0,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_phase_plan
        FOREIGN KEY (plan_id) REFERENCES plan_instance(plan_id) ON DELETE CASCADE,
    CONSTRAINT uk_plan_phase_order UNIQUE (plan_id, phase_order)
);

COMMENT ON TABLE plan_phase IS '计划阶段表';
COMMENT ON COLUMN plan_phase.phase_order IS '阶段顺序（从1开始）';
COMMENT ON COLUMN plan_phase.node_ids IS '阶段包含的节点ID列表(JSON)';
COMMENT ON COLUMN plan_phase.status IS '阶段状态编码：0-PENDING,1-RUNNING,2-SUCCESS,3-FAILED';

-- =========================================================
-- 4) 任务节点表
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_node (
    node_id                  VARCHAR(64) PRIMARY KEY,
    plan_id                  VARCHAR(64) NOT NULL,
    phase_id                 VARCHAR(64),
    name                     VARCHAR(255) NOT NULL,
    node_type                SMALLINT NOT NULL,
    input_json               JSONB,
    expected_output_schema   JSONB,
    dependencies             JSONB,
    parallel_group           VARCHAR(64),
    status                   SMALLINT NOT NULL DEFAULT 0,
    retry_policy             JSONB,
    retry_count              INTEGER NOT NULL DEFAULT 0,
    max_retry                INTEGER NOT NULL DEFAULT 0,
    model_hint               SMALLINT,
    resource_hint            JSONB,
    output_json              JSONB,
    output_for_next          JSONB,
    fail_reason              TEXT,
    last_error_stack_brief   TEXT,
    risk_level               SMALLINT NOT NULL DEFAULT 0,
    cost_ms                  BIGINT,
    started_at               TIMESTAMP,
    finished_at              TIMESTAMP,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_node_plan
        FOREIGN KEY (plan_id) REFERENCES plan_instance(plan_id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_node_phase
        FOREIGN KEY (phase_id) REFERENCES plan_phase(phase_id) ON DELETE SET NULL
);

COMMENT ON TABLE plan_node IS '计划任务节点表';
COMMENT ON COLUMN plan_node.node_type IS '节点类型编码：0-ANALYZE,1-TOOL,2-SKILL,3-VALIDATE,4-SUMMARIZE,5-REPORT,6-CODE';
COMMENT ON COLUMN plan_node.dependencies IS '依赖节点ID列表(JSON)';
COMMENT ON COLUMN plan_node.parallel_group IS '并行组标识';
COMMENT ON COLUMN plan_node.status IS '节点状态编码：0-PENDING,1-RUNNING,2-SUCCESS,3-FAILED,4-BLOCKED,5-APPROVAL_PENDING,6-SKIPPED';
COMMENT ON COLUMN plan_node.retry_policy IS '重试策略(JSON)';
COMMENT ON COLUMN plan_node.model_hint IS '模型提示编码：0-SMALL,1-MID,2-BIG,3-FLASH';
COMMENT ON COLUMN plan_node.resource_hint IS '候选资源提示(JSON)';
COMMENT ON COLUMN plan_node.output_for_next IS '传递给下游节点的关键数据';
COMMENT ON COLUMN plan_node.risk_level IS '风险等级编码：0-LOW,1-MEDIUM,2-HIGH';
COMMENT ON COLUMN plan_node.cost_ms IS '节点执行耗时(毫秒)';

-- =========================================================
-- 5) 节点依赖边表
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_edge (
    id                   BIGSERIAL PRIMARY KEY,
    plan_id              VARCHAR(64) NOT NULL,
    from_node_id         VARCHAR(64) NOT NULL,
    to_node_id           VARCHAR(64) NOT NULL,
    condition_expr       TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_edge_plan
        FOREIGN KEY (plan_id) REFERENCES plan_instance(plan_id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_edge_from
        FOREIGN KEY (from_node_id) REFERENCES plan_node(node_id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_edge_to
        FOREIGN KEY (to_node_id) REFERENCES plan_node(node_id) ON DELETE CASCADE,
    CONSTRAINT uk_plan_edge_unique UNIQUE (plan_id, from_node_id, to_node_id)
);

COMMENT ON TABLE plan_edge IS '节点依赖边（DAG）';
COMMENT ON COLUMN plan_edge.condition_expr IS '条件表达式（可选）';

-- =========================================================
-- 6) 计划事件日志表（用于前端流程可视化与审计）
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_event_log (
    event_id              BIGSERIAL PRIMARY KEY,
    plan_id               VARCHAR(64) NOT NULL,
    phase_id              VARCHAR(64),
    node_id               VARCHAR(64),
    event_type            SMALLINT NOT NULL,
    event_payload         JSONB,
    trace_id              VARCHAR(128),
    level                 SMALLINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_event_plan
        FOREIGN KEY (plan_id) REFERENCES plan_instance(plan_id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_event_phase
        FOREIGN KEY (phase_id) REFERENCES plan_phase(phase_id) ON DELETE SET NULL,
    CONSTRAINT fk_plan_event_node
        FOREIGN KEY (node_id) REFERENCES plan_node(node_id) ON DELETE SET NULL
);

COMMENT ON TABLE plan_event_log IS '计划执行事件日志（SSE/调度/重试/审批）';
COMMENT ON COLUMN plan_event_log.event_type IS '事件类型编码：0-PLAN_CREATED,1-PLAN_PHASE_STARTED,2-PLAN_PHASE_FINISHED,3-PLAN_NODE_RUNNING,4-PLAN_NODE_SUCCESS,5-PLAN_NODE_FAILED,6-PLAN_REPLANNED,7-PLAN_FINISHED,8-PLAN_REPORT_READY,9-PLAN_CODE_PATCH_READY,10-PLAN_TEST_RESULT,11-APPROVAL_REQUEST,12-APPROVAL_RESULT';
COMMENT ON COLUMN plan_event_log.level IS '日志级别编码：0-INFO,1-WARN,2-ERROR';
COMMENT ON COLUMN plan_event_log.event_payload IS '事件载荷JSON';

-- =========================================================
-- 7) 计划检查点表（恢复/回滚）
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_checkpoint (
    checkpoint_id         VARCHAR(64) PRIMARY KEY,
    plan_id               VARCHAR(64) NOT NULL,
    phase_id              VARCHAR(64),
    node_id               VARCHAR(64),
    checkpoint_data       JSONB NOT NULL,
    snapshot_hash         VARCHAR(128),
    created_by            VARCHAR(64),
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_checkpoint_plan
        FOREIGN KEY (plan_id) REFERENCES plan_instance(plan_id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_checkpoint_phase
        FOREIGN KEY (phase_id) REFERENCES plan_phase(phase_id) ON DELETE SET NULL,
    CONSTRAINT fk_plan_checkpoint_node
        FOREIGN KEY (node_id) REFERENCES plan_node(node_id) ON DELETE SET NULL
);

COMMENT ON TABLE plan_checkpoint IS '计划恢复点/快照';
COMMENT ON COLUMN plan_checkpoint.checkpoint_data IS '检查点完整数据(JSON)';

-- =========================================================
-- 8) 计划报告表（HTML报告）
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_report (
    report_id             BIGSERIAL PRIMARY KEY,
    plan_id               VARCHAR(64) NOT NULL,
    session_id            VARCHAR(128),
    final_status          SMALLINT NOT NULL,
    report_title          VARCHAR(255),
    summary               TEXT,
    report_path           TEXT,
    report_url            TEXT,
    open_result           SMALLINT,
    report_html           TEXT,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_report_plan
        FOREIGN KEY (plan_id) REFERENCES plan_instance(plan_id) ON DELETE CASCADE
);

COMMENT ON TABLE plan_report IS '任务报告（HTML）';
COMMENT ON COLUMN plan_report.final_status IS '最终状态编码：0-SUCCESS,1-FAILED,2-PARTIAL,3-CANCELLED';
COMMENT ON COLUMN plan_report.open_result IS '浏览器唤起结果编码：0-SUCCESS,1-FAILED';
COMMENT ON COLUMN plan_report.report_html IS '可选：直接存储HTML内容（较大时建议仅存路径）';

-- =========================================================
-- 索引区
-- =========================================================

-- plan_instance
CREATE INDEX IF NOT EXISTS idx_plan_instance_session_id
    ON plan_instance(session_id);

CREATE INDEX IF NOT EXISTS idx_plan_instance_status
    ON plan_instance(status);

CREATE INDEX IF NOT EXISTS idx_plan_instance_created_at
    ON plan_instance(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_instance_updated_at
    ON plan_instance(updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_instance_status_updated_at
    ON plan_instance(status, updated_at DESC);

-- plan_blueprint
CREATE INDEX IF NOT EXISTS idx_plan_blueprint_plan_id
    ON plan_blueprint(plan_id);

CREATE INDEX IF NOT EXISTS idx_plan_blueprint_generated_at
    ON plan_blueprint(generated_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_blueprint_json_gin
    ON plan_blueprint USING gin (blueprint_json);

-- plan_phase
CREATE INDEX IF NOT EXISTS idx_plan_phase_plan_id
    ON plan_phase(plan_id);

CREATE INDEX IF NOT EXISTS idx_plan_phase_status
    ON plan_phase(status);

CREATE INDEX IF NOT EXISTS idx_plan_phase_plan_status
    ON plan_phase(plan_id, status);

-- plan_node
CREATE INDEX IF NOT EXISTS idx_plan_node_plan_id
    ON plan_node(plan_id);

CREATE INDEX IF NOT EXISTS idx_plan_node_phase_id
    ON plan_node(phase_id);

CREATE INDEX IF NOT EXISTS idx_plan_node_status
    ON plan_node(status);

CREATE INDEX IF NOT EXISTS idx_plan_node_plan_status
    ON plan_node(plan_id, status);

CREATE INDEX IF NOT EXISTS idx_plan_node_phase_status
    ON plan_node(phase_id, status);

CREATE INDEX IF NOT EXISTS idx_plan_node_parallel_group
    ON plan_node(parallel_group);

CREATE INDEX IF NOT EXISTS idx_plan_node_risk_level
    ON plan_node(risk_level);

CREATE INDEX IF NOT EXISTS idx_plan_node_model_hint
    ON plan_node(model_hint);

CREATE INDEX IF NOT EXISTS idx_plan_node_created_at
    ON plan_node(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_node_dependencies_gin
    ON plan_node USING gin (dependencies);

CREATE INDEX IF NOT EXISTS idx_plan_node_resource_hint_gin
    ON plan_node USING gin (resource_hint);

CREATE INDEX IF NOT EXISTS idx_plan_node_output_for_next_gin
    ON plan_node USING gin (output_for_next);

-- plan_edge
CREATE INDEX IF NOT EXISTS idx_plan_edge_plan_id
    ON plan_edge(plan_id);

CREATE INDEX IF NOT EXISTS idx_plan_edge_from_node
    ON plan_edge(from_node_id);

CREATE INDEX IF NOT EXISTS idx_plan_edge_to_node
    ON plan_edge(to_node_id);

-- plan_event_log
CREATE INDEX IF NOT EXISTS idx_plan_event_plan_id
    ON plan_event_log(plan_id);

CREATE INDEX IF NOT EXISTS idx_plan_event_phase_id
    ON plan_event_log(phase_id);

CREATE INDEX IF NOT EXISTS idx_plan_event_node_id
    ON plan_event_log(node_id);

CREATE INDEX IF NOT EXISTS idx_plan_event_type
    ON plan_event_log(event_type);

CREATE INDEX IF NOT EXISTS idx_plan_event_trace_id
    ON plan_event_log(trace_id);

CREATE INDEX IF NOT EXISTS idx_plan_event_created_at
    ON plan_event_log(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_event_plan_type_time
    ON plan_event_log(plan_id, event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_event_payload_gin
    ON plan_event_log USING gin (event_payload);

-- plan_checkpoint
CREATE INDEX IF NOT EXISTS idx_plan_checkpoint_plan_id
    ON plan_checkpoint(plan_id);

CREATE INDEX IF NOT EXISTS idx_plan_checkpoint_plan_time
    ON plan_checkpoint(plan_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_checkpoint_phase_node
    ON plan_checkpoint(plan_id, phase_id, node_id);

CREATE INDEX IF NOT EXISTS idx_plan_checkpoint_hash
    ON plan_checkpoint(snapshot_hash);

CREATE INDEX IF NOT EXISTS idx_plan_checkpoint_data_gin
    ON plan_checkpoint USING gin (checkpoint_data);

-- plan_report
CREATE INDEX IF NOT EXISTS idx_plan_report_plan_id
    ON plan_report(plan_id);

CREATE INDEX IF NOT EXISTS idx_plan_report_session_id
    ON plan_report(session_id);

CREATE INDEX IF NOT EXISTS idx_plan_report_final_status
    ON plan_report(final_status);

CREATE INDEX IF NOT EXISTS idx_plan_report_created_at
    ON plan_report(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_plan_report_status_time
    ON plan_report(final_status, created_at DESC);
