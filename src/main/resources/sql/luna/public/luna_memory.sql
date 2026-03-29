create table luna_memory
(
    id          bigserial
        primary key,
    session_id  varchar(255),
    memory_type smallint,
    content     text,
    weight      integer   default 1,
    created_at  timestamp default CURRENT_TIMESTAMP,
    updated_at  timestamp default CURRENT_TIMESTAMP,
    embedding   vector(768)
);

comment on table luna_memory is '長期記憶表';

comment on column luna_memory.id is '主鍵 ID';

comment on column luna_memory.session_id is '會話 ID';

comment on column luna_memory.memory_type is '記憶類型: 0-FACT, 1-PREFERENCE, 2-SUMMARY, 3-REFLECTION ';

comment on column luna_memory.content is '記憶內容';

comment on column luna_memory.weight is '權重，用於標識記憶的重要性，默認為 1';

comment on column luna_memory.created_at is '創建時間';

comment on column luna_memory.updated_at is '更新時間';

alter table luna_memory
    owner to yilena;

create index idx_luna_memory_session_id
    on luna_memory (session_id);

create index idx_luna_memory_memory_type
    on luna_memory (memory_type);

create index idx_luna_memory_session_type
    on luna_memory (session_id, memory_type);

create index idx_luna_memory_created_at
    on luna_memory (created_at desc);

create index idx_luna_memory_weight
    on luna_memory (weight desc);

