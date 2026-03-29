create table mcp_resource_catalog
(
    id           bigint       not null
        primary key,
    server_code  varchar(100) not null,
    resource_uri varchar(500) not null,
    name         varchar(255),
    description  text,
    mime_type    varchar(100),
    annotations  jsonb,
    raw_payload  jsonb,
    tags         jsonb     default '[]'::jsonb,
    enabled      boolean   default true,
    embedding    vector(768),
    synced_at    timestamp default CURRENT_TIMESTAMP,
    created_at   timestamp default CURRENT_TIMESTAMP,
    updated_at   timestamp default CURRENT_TIMESTAMP,
    unique (server_code, resource_uri)
);

comment on table mcp_resource_catalog is 'MCP resource catalog cache';

alter table mcp_resource_catalog
    owner to yilena;

create index idx_mcp_resource_catalog_server_code
    on mcp_resource_catalog (server_code);

create index idx_mcp_resource_catalog_enabled
    on mcp_resource_catalog (enabled);

create index idx_mcp_resource_catalog_synced_at
    on mcp_resource_catalog (synced_at desc);

create index idx_mcp_resource_catalog_tags_gin
    on mcp_resource_catalog using gin (tags);
