create table tasks
(
    task_id      bigint      not null
        primary key,
    resource_id  bigint      not null,
    status       varchar(20) not null,
    server_code  varchar(100),
    tool_name    varchar(200),
    approval_id  varchar(100),
    session_id   varchar(100),
    input_args   text,
    result       text,
    error_code   varchar(100),
    created_at   timestamp default CURRENT_TIMESTAMP,
    updated_at   timestamp default CURRENT_TIMESTAMP
);

comment on table tasks is '任務表 - 用於異步任務或審批流';

comment on column tasks.task_id is '任務 ID (雪花算法)';

comment on column tasks.resource_id is '關聯的資源 ID';

comment on column tasks.status is 'PENDING, RUNNING, COMPLETED, REJECTED, PENDING_APPROVAL';

comment on column tasks.server_code is 'MCP server code for task tracing';

comment on column tasks.tool_name is 'MCP tool/workflow name for task tracing';

comment on column tasks.approval_id is 'Approval task id linkage';

comment on column tasks.session_id is 'Session id/jti linkage';

comment on column tasks.input_args is '執行參數';

comment on column tasks.result is '執行結果';

comment on column tasks.error_code is 'Structured error code';

alter table tasks
    owner to yilena;

create index idx_tasks_status
    on tasks (status);

create index idx_tasks_resource_id
    on tasks (resource_id);

create index idx_tasks_server_code_tool_name
    on tasks (server_code, tool_name);

create index idx_tasks_approval_id
    on tasks (approval_id);

create index idx_tasks_session_id
    on tasks (session_id);

create index idx_tasks_created_at
    on tasks (created_at desc);

create index idx_tasks_status_created_at
    on tasks (status asc, created_at desc);
