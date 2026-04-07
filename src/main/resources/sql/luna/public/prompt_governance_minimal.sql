create table if not exists prompt_category
(
    id                    bigint       not null primary key,
    category_key          varchar(100) not null,
    category_name         varchar(200) not null,
    parent_category_key   varchar(100),
    description           text,
    sort_order            integer      not null default 0,
    keyword_match_allowed boolean      not null default true,
    is_execution_category boolean      not null default false,
    enabled               boolean      not null default true,
    created_at            timestamp    not null default current_timestamp,
    updated_at            timestamp    not null default current_timestamp,
    unique (category_key)
);

create index if not exists idx_prompt_category_enabled_sort
    on prompt_category (enabled, sort_order desc);

create table if not exists prompt_item
(
    id                     bigint       not null primary key,
    category               varchar(100) not null,
    sub_category           varchar(100),
    prompt_key             varchar(200) not null,
    prompt_name            varchar(255),
    runtime_slot           varchar(200),
    has_template_variables boolean      not null default false,
    keyword_match_enabled  boolean      not null default true,
    assembly_mode          varchar(40)  not null default 'ALWAYS',
    enabled                boolean      not null default true,
    priority               integer      not null default 80,
    status                 varchar(30)  not null default 'active',
    current_version_id     bigint,
    is_builtin             boolean      not null default false,
    description            text,
    created_at             timestamp    not null default current_timestamp,
    updated_at             timestamp    not null default current_timestamp,
    unique (prompt_key)
);

create index if not exists idx_prompt_item_category_enabled
    on prompt_item (category, enabled);

create index if not exists idx_prompt_item_current_version
    on prompt_item (current_version_id);

create table if not exists prompt_item_version
(
    id                 bigint      not null primary key,
    prompt_item_id     bigint      not null,
    version_no         varchar(50) not null,
    version_label      varchar(120),
    prompt_value       text        not null default '',
    template_variables jsonb       not null default '[]'::jsonb,
    match_keywords     jsonb       not null default '[]'::jsonb,
    match_scope        jsonb       not null default '{}'::jsonb,
    edit_policy        jsonb       not null default '{}'::jsonb,
    status             varchar(30) not null default 'active',
    change_note        text,
    is_active          boolean     not null default false,
    created_at         timestamp   not null default current_timestamp,
    updated_at         timestamp   not null default current_timestamp,
    unique (prompt_item_id, version_no)
);

create index if not exists idx_prompt_item_version_item_active
    on prompt_item_version (prompt_item_id, is_active);

create table if not exists prompt_runtime_snapshot_ref
(
    id                     bigint      not null primary key,
    session_id             varchar(120),
    round_id               bigint,
    snapshot_id            varchar(160) not null,
    prompt_item_id         bigint,
    prompt_item_version_id bigint,
    prompt_key             varchar(200),
    prompt_version_no      varchar(50),
    policy_id              varchar(120),
    assembler_version      varchar(80),
    runtime_slot           varchar(200),
    match_reason           varchar(120),
    resolved_value         text,
    created_at             timestamp   not null default current_timestamp
);

create index if not exists idx_prompt_snapshot_ref_round
    on prompt_runtime_snapshot_ref (session_id, round_id, created_at desc);

create index if not exists idx_prompt_snapshot_ref_snapshot
    on prompt_runtime_snapshot_ref (snapshot_id);
