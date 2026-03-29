create table luna_log
(
    id            bigserial
        primary key,
    log_type      varchar(50) not null,
    module        varchar(100),
    action        varchar(100),
    content       text,
    request_data  jsonb,
    response_data jsonb,
    error_message text,
    error_stack   text,
    cost_time     bigint,
    operator_id   varchar(64),
    trace_id      varchar(128),
    create_at     timestamp default CURRENT_TIMESTAMP
);

alter table luna_log
    owner to yilena;

create index idx_luna_log_type
    on luna_log (log_type);

create index idx_luna_log_module
    on luna_log (module);

create index idx_luna_log_create_at
    on luna_log (create_at);

create index idx_luna_log_action
    on luna_log (action);

create index idx_luna_log_trace_id
    on luna_log (trace_id);

create index idx_luna_log_operator_id
    on luna_log (operator_id);

create index idx_luna_log_module_action_create_at
    on luna_log (module asc, action asc, create_at desc);

create index idx_luna_log_error_only
    on luna_log (create_at desc)
    where ((log_type)::text = 'ERROR'::text);

create index idx_luna_log_request_data_gin
    on luna_log using gin (request_data);

create index idx_luna_log_response_data_gin
    on luna_log using gin (response_data);

