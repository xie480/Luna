create table tasks
(
    task_id     bigint      not null
        primary key,
    resource_id bigint      not null,
    status      varchar(20) not null,
    input_args  text,
    result      text,
    created_at  timestamp default CURRENT_TIMESTAMP,
    updated_at  timestamp default CURRENT_TIMESTAMP
);

comment on table tasks is '任務表 - 用於異步任務或審批流';

comment on column tasks.task_id is '任務 ID (雪花算法)';

comment on column tasks.resource_id is '關聯的資源 ID';

comment on column tasks.status is 'PENDING, RUNNING, COMPLETED, REJECTED, PENDING_APPROVAL';

comment on column tasks.input_args is '執行參數';

comment on column tasks.result is '執行結果';

alter table tasks
    owner to yilena;

create index idx_tasks_status
    on tasks (status);

create index idx_tasks_resource_id
    on tasks (resource_id);

create index idx_tasks_created_at
    on tasks (created_at desc);

create index idx_tasks_status_created_at
    on tasks (status asc, created_at desc);

