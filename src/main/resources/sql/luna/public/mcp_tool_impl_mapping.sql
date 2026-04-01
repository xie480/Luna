create table mcp_tool_impl_mapping
(
    id           bigint       not null
        primary key,
    server_code  varchar(100) not null,
    tool_name    varchar(200) not null,
    impl_type    varchar(50)  not null,
    bean_name    varchar(100),
    method_name  varchar(100),
    route_uri    varchar(500),
    timeout_ms   integer   default 10000,
    retry_policy jsonb,
    enabled      boolean   default true,
    created_at   timestamp default CURRENT_TIMESTAMP,
    updated_at   timestamp default CURRENT_TIMESTAMP,
    unique (server_code, tool_name)
);

comment on table mcp_tool_impl_mapping is 'Tool to implementation routing table, internal for MCP server';
comment on column mcp_tool_impl_mapping.impl_type is 'HTTP/RPC/WORKFLOW';

alter table mcp_tool_impl_mapping
    owner to yilena;

create index idx_mcp_tool_impl_mapping_server_code
    on mcp_tool_impl_mapping (server_code);

create index idx_mcp_tool_impl_mapping_enabled
    on mcp_tool_impl_mapping (enabled);

create index idx_mcp_tool_impl_mapping_impl_type
    on mcp_tool_impl_mapping (impl_type);
