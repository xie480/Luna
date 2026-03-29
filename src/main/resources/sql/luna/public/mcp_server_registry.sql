create table mcp_server_registry
(
    id            bigint       not null
        primary key,
    server_code   varchar(100) not null
        unique,
    server_name   varchar(200) not null,
    description   text,
    base_url      varchar(500),
    transport_type varchar(50) not null,
    auth_type     varchar(50),
    auth_config   jsonb,
    enabled       boolean   default true,
    health_status varchar(20) default 'UNKNOWN',
    last_sync_at  timestamp,
    created_at    timestamp default CURRENT_TIMESTAMP,
    updated_at    timestamp default CURRENT_TIMESTAMP
);

comment on table mcp_server_registry is 'MCP server registry';
comment on column mcp_server_registry.transport_type is 'HTTP/SSE/WS/STDIO';

alter table mcp_server_registry
    owner to yilena;

create index idx_mcp_server_registry_enabled
    on mcp_server_registry (enabled);

create index idx_mcp_server_registry_health_status
    on mcp_server_registry (health_status);

create index idx_mcp_server_registry_last_sync_at
    on mcp_server_registry (last_sync_at desc);

insert into mcp_server_registry
(id, server_code, server_name, description, base_url, transport_type, enabled)
values
    (1, 'local-agent-server', 'Local Agent MCP Server', 'Local Spring-hosted MCP provider', 'http://localhost:8001/mcp', 'HTTP', true)
on conflict (server_code) do nothing;
