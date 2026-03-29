create table user_preference
(
    id          bigserial
        primary key,
    pref_key    varchar(255) not null,
    pref_value  varchar(500),
    description text,
    created_at  timestamp default CURRENT_TIMESTAMP,
    updated_at  timestamp default CURRENT_TIMESTAMP,
    embedding   vector(768),
    deleted     integer   default 0
);

comment on table user_preference is '用戶畫像/偏好表，用於存儲用戶的關鍵設定';

comment on column user_preference.id is '主鍵 ID';

comment on column user_preference.pref_key is '偏好鍵';

comment on column user_preference.pref_value is '偏好值 ';

comment on column user_preference.description is '描述/備註 (用於輔助模型理解該設定的上下文)';

comment on column user_preference.created_at is '創建時間';

comment on column user_preference.updated_at is '更新時間';

alter table user_preference
    owner to yilena;

create index idx_user_preference_pref_key
    on user_preference (pref_key);

create index idx_user_preference_created_at
    on user_preference (created_at desc);

create index idx_user_preference_pref_value_trgm
    on user_preference using gin (pref_value gin_trgm_ops);

create index idx_user_preference_description_trgm
    on user_preference using gin (description gin_trgm_ops);

