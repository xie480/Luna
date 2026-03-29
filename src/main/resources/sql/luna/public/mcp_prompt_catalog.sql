create table mcp_prompt_catalog
(
    id              bigint       not null
        primary key,
    server_code     varchar(100) not null,
    prompt_name     varchar(200) not null,
    title           varchar(255),
    description     text,
    arguments_schema jsonb,
    raw_payload     jsonb,
    tags            jsonb     default '[]'::jsonb,
    enabled         boolean   default true,
    version         varchar(50),
    embedding       vector(768),
    synced_at       timestamp default CURRENT_TIMESTAMP,
    created_at      timestamp default CURRENT_TIMESTAMP,
    updated_at      timestamp default CURRENT_TIMESTAMP,
    unique (server_code, prompt_name)
);

comment on table mcp_prompt_catalog is 'MCP prompt catalog cache';

alter table mcp_prompt_catalog
    owner to yilena;

create index idx_mcp_prompt_catalog_server_code
    on mcp_prompt_catalog (server_code);

create index idx_mcp_prompt_catalog_enabled
    on mcp_prompt_catalog (enabled);

create index idx_mcp_prompt_catalog_synced_at
    on mcp_prompt_catalog (synced_at desc);

create index idx_mcp_prompt_catalog_tags_gin
    on mcp_prompt_catalog using gin (tags);
