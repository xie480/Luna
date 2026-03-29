create table plan_phase
(
    phase_id       varchar(64)                         not null
        primary key,
    plan_id        varchar(64)                         not null
        constraint fk_plan_phase_plan
            references plan_instance
            on delete cascade,
    phase_order    integer                             not null,
    name           varchar(255)                        not null,
    objective      text,
    node_ids       jsonb,
    entry_criteria text,
    exit_criteria  text,
    status         smallint  default 0                 not null,
    started_at     timestamp,
    finished_at    timestamp,
    created_at     timestamp default CURRENT_TIMESTAMP not null,
    updated_at     timestamp default CURRENT_TIMESTAMP not null,
    constraint uk_plan_phase_order
        unique (plan_id, phase_order)
);

comment on table plan_phase is '计划阶段表';

comment on column plan_phase.phase_order is '阶段顺序（从1开始）';

comment on column plan_phase.node_ids is '阶段包含的节点ID列表(JSON)';

comment on column plan_phase.status is '阶段状态编码：0-PENDING,1-RUNNING,2-SUCCESS,3-FAILED';

alter table plan_phase
    owner to yilena;

create index idx_plan_phase_plan_id
    on plan_phase (plan_id);

create index idx_plan_phase_status
    on plan_phase (status);

create index idx_plan_phase_plan_status
    on plan_phase (plan_id, status);

