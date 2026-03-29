create table plan_blueprint
(
    id                 bigserial
        primary key,
    plan_id            varchar(64)                         not null
        constraint fk_plan_blueprint_plan
            references plan_instance
            on delete cascade,
    plan_version       integer                             not null,
    blueprint_json     jsonb                               not null,
    generated_by_model varchar(64),
    generated_at       timestamp default CURRENT_TIMESTAMP not null,
    created_at         timestamp default CURRENT_TIMESTAMP not null,
    constraint uk_plan_blueprint_plan_version
        unique (plan_id, plan_version)
);

comment on table plan_blueprint is '全局规划蓝图（按版本存档）';

comment on column plan_blueprint.blueprint_json is '完整蓝图JSON（phase/node/edge/risk）';

comment on column plan_blueprint.generated_by_model is '生成蓝图的模型标识';

comment on column plan_blueprint.generated_at is '模型生成时间';

alter table plan_blueprint
    owner to yilena;

create index idx_plan_blueprint_plan_id
    on plan_blueprint (plan_id);

create index idx_plan_blueprint_generated_at
    on plan_blueprint (generated_at desc);

create index idx_plan_blueprint_json_gin
    on plan_blueprint using gin (blueprint_json);

