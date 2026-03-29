create table plan_instance
(
    plan_id            varchar(64)                         not null
        primary key,
    session_id         varchar(128)                        not null,
    user_goal          text                                not null,
    constraints_json   jsonb,
    success_criteria   text,
    planning_model     varchar(64),
    plan_version       integer   default 1                 not null,
    status             smallint  default 0                 not null,
    current_loop_index integer   default 0                 not null,
    final_status       smallint,
    error_message      text,
    started_at         timestamp,
    finished_at        timestamp,
    created_at         timestamp default CURRENT_TIMESTAMP not null,
    updated_at         timestamp default CURRENT_TIMESTAMP not null
);

comment on table plan_instance is '编排计划实例主表';

comment on column plan_instance.plan_id is '计划ID（可用雪花ID/UUID）';

comment on column plan_instance.session_id is '会话ID（建议使用JWT jti）';

comment on column plan_instance.user_goal is '用户目标原文';

comment on column plan_instance.constraints_json is '约束条件(JSON)';

comment on column plan_instance.success_criteria is '成功标准';

comment on column plan_instance.planning_model is '规划所用模型';

comment on column plan_instance.plan_version is '计划版本号';

comment on column plan_instance.status is '计划状态编码：0-PENDING,1-RUNNING,2-WAITING_USER_APPROVAL,3-SUCCESS,4-FAILED,5-CANCELLED';

comment on column plan_instance.current_loop_index is '当前loop轮次';

comment on column plan_instance.final_status is '最终状态编码：0-SUCCESS,1-FAILED,2-PARTIAL,3-CANCELLED';

comment on column plan_instance.error_message is '失败原因';

comment on column plan_instance.started_at is '计划开始时间';

comment on column plan_instance.finished_at is '计划结束时间';

comment on column plan_instance.created_at is '创建时间';

comment on column plan_instance.updated_at is '更新时间';

alter table plan_instance
    owner to yilena;

create index idx_plan_instance_session_id
    on plan_instance (session_id);

create index idx_plan_instance_status
    on plan_instance (status);

create index idx_plan_instance_created_at
    on plan_instance (created_at desc);

create index idx_plan_instance_updated_at
    on plan_instance (updated_at desc);

create index idx_plan_instance_status_updated_at
    on plan_instance (status asc, updated_at desc);

