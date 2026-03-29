create table schedule_task
(
    id           bigserial
        primary key,
    content      text,
    trigger_time timestamp,
    status       smallint,
    task_type    smallint,
    created_at   timestamp default CURRENT_TIMESTAMP,
    updated_at   timestamp default CURRENT_TIMESTAMP,
    deleted      integer   default 0
);

comment on table schedule_task is '日程與待辦事項表，用於 Luna 主動提醒或執行任務';

comment on column schedule_task.id is '主鍵 ID';

comment on column schedule_task.content is '任務內容';

comment on column schedule_task.trigger_time is '觸發時間 (如果是提醒類任務)';

comment on column schedule_task.status is '狀態: 0-待處理, 1-已完成, 2-已取消, 3-已過期 ';

comment on column schedule_task.task_type is '任務類型: 0-REMINDER, 1-ACTION, 2-TODO ';

comment on column schedule_task.created_at is '創建時間';

comment on column schedule_task.updated_at is '更新時間';

alter table schedule_task
    owner to yilena;

create index idx_schedule_task_status
    on schedule_task (status);

create index idx_schedule_task_task_type
    on schedule_task (task_type);

create index idx_schedule_task_trigger_time
    on schedule_task (trigger_time);

create index idx_schedule_task_status_trigger_time
    on schedule_task (status, trigger_time);

create index idx_schedule_task_created_at
    on schedule_task (created_at desc);

