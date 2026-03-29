create table plan_node
(
    node_id                varchar(64)                         not null
        primary key,
    plan_id                varchar(64)                         not null
        constraint fk_plan_node_plan
            references plan_instance
            on delete cascade,
    phase_id               varchar(64)
        constraint fk_plan_node_phase
            references plan_phase
            on delete set null,
    name                   varchar(255)                        not null,
    node_type              smallint                            not null,
    capability_type        varchar(50),
    capability_name        varchar(200),
    server_code            varchar(100),
    input_json             jsonb,
    resolved_input_json    jsonb,
    expected_output_schema jsonb,
    dependencies           jsonb,
    parallel_group         varchar(64),
    status                 smallint  default 0                 not null,
    approval_required      boolean   default false             not null,
    approval_status        varchar(50),
    retry_policy           jsonb,
    retry_count            integer   default 0                 not null,
    max_retry              integer   default 0                 not null,
    model_hint             smallint,
    resource_hint          jsonb,
    output_json            jsonb,
    output_for_next        jsonb,
    fail_reason            text,
    last_error_stack_brief text,
    risk_level             smallint  default 0                 not null,
    cost_ms                bigint,
    started_at             timestamp,
    finished_at            timestamp,
    created_at             timestamp default CURRENT_TIMESTAMP not null,
    updated_at             timestamp default CURRENT_TIMESTAMP not null
);

comment on table plan_node is '计划任务节点�?;

comment on column plan_node.node_type is '0-ANALYZE,1-TOOL,2-SKILL(legacy),3-VALIDATE,4-SUMMARIZE(legacy),5-REPORT,6-CODE,7-PROMPT,8-RESOURCE,9-WORKFLOW';

comment on column plan_node.dependencies is '依赖节点ID列表(JSON)';

comment on column plan_node.parallel_group is '并行组标�?;

comment on column plan_node.status is '节点状态编码：0-PENDING,1-RUNNING,2-SUCCESS,3-FAILED,4-BLOCKED,5-APPROVAL_PENDING,6-SKIPPED';

comment on column plan_node.retry_policy is '重试策略(JSON)';

comment on column plan_node.model_hint is '模型提示编码�?-SMALL,1-MID,2-BIG,3-FLASH';

comment on column plan_node.resource_hint is '候选资源提�?JSON)';

comment on column plan_node.output_for_next is '传递给下游节点的关键数�?;

comment on column plan_node.risk_level is '风险等级编码�?-LOW,1-MEDIUM,2-HIGH';

comment on column plan_node.cost_ms is '节点执行耗时(毫秒)';

alter table plan_node
    owner to yilena;

create index idx_plan_node_plan_id
    on plan_node (plan_id);

create index idx_plan_node_phase_id
    on plan_node (phase_id);

create index idx_plan_node_status
    on plan_node (status);

create index idx_plan_node_plan_status
    on plan_node (plan_id, status);

create index idx_plan_node_phase_status
    on plan_node (phase_id, status);

create index idx_plan_node_parallel_group
    on plan_node (parallel_group);

create index idx_plan_node_server_code
    on plan_node (server_code);

create index idx_plan_node_capability_type
    on plan_node (capability_type);

create index idx_plan_node_approval_status
    on plan_node (approval_status);

create index idx_plan_node_risk_level
    on plan_node (risk_level);

create index idx_plan_node_model_hint
    on plan_node (model_hint);

create index idx_plan_node_created_at
    on plan_node (created_at desc);

create index idx_plan_node_dependencies_gin
    on plan_node using gin (dependencies);

create index idx_plan_node_resource_hint_gin
    on plan_node using gin (resource_hint);

create index idx_plan_node_resolved_input_json_gin
    on plan_node using gin (resolved_input_json);

create index idx_plan_node_output_for_next_gin
    on plan_node using gin (output_for_next);

