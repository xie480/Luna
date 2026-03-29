create table plan_edge
(
    id             bigserial
        primary key,
    plan_id        varchar(64)                         not null
        constraint fk_plan_edge_plan
            references plan_instance
            on delete cascade,
    from_node_id   varchar(64)                         not null
        constraint fk_plan_edge_from
            references plan_node
            on delete cascade,
    to_node_id     varchar(64)                         not null
        constraint fk_plan_edge_to
            references plan_node
            on delete cascade,
    condition_expr text,
    created_at     timestamp default CURRENT_TIMESTAMP not null,
    constraint uk_plan_edge_unique
        unique (plan_id, from_node_id, to_node_id)
);

comment on table plan_edge is '节点依赖边（DAG）';

comment on column plan_edge.condition_expr is '条件表达式（可选）';

alter table plan_edge
    owner to yilena;

create index idx_plan_edge_plan_id
    on plan_edge (plan_id);

create index idx_plan_edge_from_node
    on plan_edge (from_node_id);

create index idx_plan_edge_to_node
    on plan_edge (to_node_id);

