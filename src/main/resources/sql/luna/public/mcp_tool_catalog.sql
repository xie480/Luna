create table mcp_tool_catalog
(
    id                bigint       not null
        primary key,
    server_code       varchar(100) not null,
    tool_name         varchar(200) not null,
    title             varchar(255),
    description       text,
    input_schema      jsonb        not null,
    output_schema     jsonb,
    annotations       jsonb,
    tags              jsonb      default '[]'::jsonb,
    enabled           boolean    default true,
    version           varchar(50),
    execution_mode    varchar(16) default 'MCP',
    requires_approval boolean    default false,
    sensitivity       varchar(50) default 'LOW',
    raw_payload       jsonb,
    embedding         vector(768),
    synced_at         timestamp  default CURRENT_TIMESTAMP,
    created_at        timestamp  default CURRENT_TIMESTAMP,
    updated_at        timestamp  default CURRENT_TIMESTAMP,
    unique (server_code, tool_name),
    constraint chk_mcp_tool_catalog_execution_mode
        check (upper(execution_mode) in ('LEGACY', 'MCP'))
);

comment on table mcp_tool_catalog is 'MCP tool catalog cache';

alter table mcp_tool_catalog
    owner to yilena;

create index idx_mcp_tool_catalog_server_code
    on mcp_tool_catalog (server_code);

create index idx_mcp_tool_catalog_enabled
    on mcp_tool_catalog (enabled);

create index idx_mcp_tool_catalog_requires_approval
    on mcp_tool_catalog (requires_approval);

create index idx_mcp_tool_catalog_sensitivity
    on mcp_tool_catalog (sensitivity);

create index idx_mcp_tool_catalog_synced_at
    on mcp_tool_catalog (synced_at desc);

create index idx_mcp_tool_catalog_execution_mode
    on mcp_tool_catalog (execution_mode);

create index idx_mcp_tool_catalog_tags_gin
    on mcp_tool_catalog using gin (tags);
