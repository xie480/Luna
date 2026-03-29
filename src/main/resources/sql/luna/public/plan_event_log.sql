create table plan_event_log
(
    event_id      bigserial
        primary key,
    plan_id       varchar(64)                         not null
        constraint fk_plan_event_plan
            references plan_instance
            on delete cascade,
    phase_id      varchar(64)
        constraint fk_plan_event_phase
            references plan_phase
            on delete set null,
    node_id       varchar(64)
        constraint fk_plan_event_node
            references plan_node
            on delete set null,
    event_type    smallint                            not null,
    event_payload jsonb,
    trace_id      varchar(128),
    level         smallint  default 0                 not null,
    created_at    timestamp default CURRENT_TIMESTAMP not null
);

comment on table plan_event_log is '计划执行事件日志（SSE/调度/重试/审批）';

comment on column plan_event_log.event_type is '事件类型编码：0-PLAN_CREATED,1-PLAN_PHASE_STARTED,2-PLAN_PHASE_FINISHED,3-PLAN_NODE_RUNNING,4-PLAN_NODE_SUCCESS,5-PLAN_NODE_FAILED,6-PLAN_REPLANNED,7-PLAN_FINISHED,8-PLAN_REPORT_READY,9-PLAN_CODE_PATCH_READY,10-PLAN_TEST_RESULT,11-APPROVAL_REQUEST,12-APPROVAL_RESULT';

comment on column plan_event_log.event_payload is '事件载荷JSON';

comment on column plan_event_log.level is '日志级别编码：0-INFO,1-WARN,2-ERROR';

alter table plan_event_log
    owner to yilena;

create index idx_plan_event_plan_id
    on plan_event_log (plan_id);

create index idx_plan_event_phase_id
    on plan_event_log (phase_id);

create index idx_plan_event_node_id
    on plan_event_log (node_id);

create index idx_plan_event_type
    on plan_event_log (event_type);

create index idx_plan_event_trace_id
    on plan_event_log (trace_id);

create index idx_plan_event_created_at
    on plan_event_log (created_at desc);

create index idx_plan_event_plan_type_time
    on plan_event_log (plan_id asc, event_type asc, created_at desc);

create index idx_plan_event_payload_gin
    on plan_event_log using gin (event_payload);

