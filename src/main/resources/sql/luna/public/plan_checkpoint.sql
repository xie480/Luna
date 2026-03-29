create table plan_checkpoint
(
    checkpoint_id   varchar(64)                         not null
        primary key,
    plan_id         varchar(64)                         not null
        constraint fk_plan_checkpoint_plan
            references plan_instance
            on delete cascade,
    phase_id        varchar(64)
        constraint fk_plan_checkpoint_phase
            references plan_phase
            on delete set null,
    node_id         varchar(64)
        constraint fk_plan_checkpoint_node
            references plan_node
            on delete set null,
    checkpoint_data jsonb                               not null,
    snapshot_hash   varchar(128),
    created_by      varchar(64),
    created_at      timestamp default CURRENT_TIMESTAMP not null
);

comment on table plan_checkpoint is '计划恢复点/快照';

comment on column plan_checkpoint.checkpoint_data is '检查点完整数据(JSON)';

alter table plan_checkpoint
    owner to yilena;

create index idx_plan_checkpoint_plan_id
    on plan_checkpoint (plan_id);

create index idx_plan_checkpoint_plan_time
    on plan_checkpoint (plan_id asc, created_at desc);

create index idx_plan_checkpoint_phase_node
    on plan_checkpoint (plan_id, phase_id, node_id);

create index idx_plan_checkpoint_hash
    on plan_checkpoint (snapshot_hash);

create index idx_plan_checkpoint_data_gin
    on plan_checkpoint using gin (checkpoint_data);

