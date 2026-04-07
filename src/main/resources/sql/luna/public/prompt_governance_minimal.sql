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

insert into prompt_category (id, category_key, category_name, parent_category_key, description, sort_order, keyword_match_allowed, is_execution_category, enabled)
select
    (100000 + t.ordinal)::bigint,
    t.category_key,
    t.category_name,
    null,
    t.description,
    t.sort_order,
    t.keyword_match_allowed,
    t.is_execution_category,
    true
from (
         values
             (1,  'system',      'System',       'System level prompts',                 160, false, true),
             (2,  'persona',     'Persona',      'Persona behavior prompts',             150, true,  false),
             (3,  'scene',       'Scene',        'Scene atmosphere prompts',             145, true,  false),
             (4,  'corpus',      'Corpus',       'Corpus style prompts',                 140, true,  false),
             (5,  'style',       'Style',        'Expression style prompts',             135, true,  false),
             (6,  'worldview',   'Worldview',    'World setting prompts',                130, true,  false),
             (7,  'relation',    'Relation',     'Relationship prompts',                 125, true,  false),
             (8,  'task',        'Task',         'Task strategy prompts',                120, false, true),
             (9,  'memory-hint', 'Memory Hint',  'Memory usage hints',                   115, false, true),
             (10, 'rag-hint',    'RAG Hint',     'Retrieval usage hints',                110, false, true),
             (11, 'tool',        'Tool',         'Tool execution prompts',               105, false, true),
             (12, 'format',      'Format',       'Output format prompts',                100, false, true),
             (13, 'repair',      'Repair',       'Repair prompts',                       95,  false, true),
             (14, 'summary',     'Summary',      'Summary prompts',                      90,  false, true),
             (15, 'guardrail',   'Guardrail',    'Guardrail prompts',                    85,  false, true),
             (16, 'agent-local', 'Agent Local',  'Agent local prompts',                  80,  false, true)
     ) as t(ordinal, category_key, category_name, description, sort_order, keyword_match_allowed, is_execution_category)
where not exists (
    select 1
    from prompt_category c
    where c.category_key = t.category_key
);

create table if not exists prompt_item
(
    id                     bigint       not null primary key,
    category               varchar(100) not null,
    category_key           varchar(100),
    sub_category           varchar(100),
    prompt_key             varchar(200) not null,
    prompt_name            varchar(255),
    runtime_slot           varchar(200),
    has_template_variables boolean      not null default false,
    keyword_match_enabled  boolean      not null default true,
    assembly_mode          varchar(40)  not null default 'ALWAYS',
    enabled                boolean      not null default true,
    priority               integer      not null default 80,
    status                 varchar(30)  not null default 'enabled',
    current_version_id     bigint,
    is_builtin             boolean      not null default false,
    description            text,
    created_at             timestamp    not null default current_timestamp,
    updated_at             timestamp    not null default current_timestamp,
    unique (prompt_key)
);

create index if not exists idx_prompt_item_category_enabled
    on prompt_item (category, enabled);

create index if not exists idx_prompt_item_category_key_enabled
    on prompt_item (category_key, enabled);

create index if not exists idx_prompt_item_current_version
    on prompt_item (current_version_id);

alter table prompt_item
    add column if not exists category_key varchar(100);

update prompt_item
set category_key = category
where (category_key is null or category_key = '')
  and category is not null;

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
    node_id                bigint,
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

alter table prompt_runtime_snapshot_ref
    add column if not exists node_id bigint;

create index if not exists idx_prompt_snapshot_ref_round
    on prompt_runtime_snapshot_ref (session_id, round_id, created_at desc);

create index if not exists idx_prompt_snapshot_ref_node
    on prompt_runtime_snapshot_ref (session_id, node_id, created_at desc);

create index if not exists idx_prompt_snapshot_ref_snapshot
    on prompt_runtime_snapshot_ref (snapshot_id);

create table if not exists prompt_policy
(
    id                 bigint       not null primary key,
    policy_key         varchar(120) not null,
    policy_name        varchar(200) not null,
    description        text,
    enabled            boolean      not null default true,
    current_version_id bigint,
    created_at         timestamp    not null default current_timestamp,
    updated_at         timestamp    not null default current_timestamp,
    unique (policy_key)
);

create index if not exists idx_prompt_policy_enabled_updated
    on prompt_policy (enabled, updated_at desc);

create table if not exists prompt_policy_version
(
    id                  bigint      not null primary key,
    prompt_policy_id    bigint      not null,
    version_no          varchar(50) not null,
    include_prompt_keys jsonb       not null default '[]'::jsonb,
    exclude_prompt_keys jsonb       not null default '[]'::jsonb,
    status              varchar(30) not null default 'active',
    change_note         text,
    is_active           boolean     not null default false,
    created_at          timestamp   not null default current_timestamp,
    updated_at          timestamp   not null default current_timestamp,
    unique (prompt_policy_id, version_no)
);

create index if not exists idx_prompt_policy_version_policy_active
    on prompt_policy_version (prompt_policy_id, is_active);
