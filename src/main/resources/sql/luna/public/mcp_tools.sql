create table mcp_tools
(
    id                bigint       not null
        primary key,
    name              varchar(100) not null
        unique,
    description       text,
    version           varchar(20) default '1.0.0'::character varying,
    owner             varchar(50),
    bean_name         varchar(100) not null,
    method_name       varchar(100) not null,
    input_schema      text,
    output_schema     text,
    embedding         text,
    created_at        timestamp   default CURRENT_TIMESTAMP,
    updated_at        timestamp   default CURRENT_TIMESTAMP,
    requires_approval boolean     default false,
    sensitivity       varchar(50) default 'LOW'::character varying
);

comment on table mcp_tools is 'Legacy compatibility table, read-only mirror of historical tool definitions';

comment on column mcp_tools.id is '主鍵 ID (雪花算法)';

comment on column mcp_tools.name is '工具唯一名稱';

comment on column mcp_tools.description is '工具語義描述';

comment on column mcp_tools.bean_name is 'Legacy Spring Bean 名稱 (兼容字段，Host 不再读取)';

comment on column mcp_tools.method_name is 'Legacy 執行方法名稱 (兼容字段，Host 不再读取)';

comment on column mcp_tools.input_schema is '參數 JSON Schema';

comment on column mcp_tools.output_schema is '輸出 JSON Schema';

comment on column mcp_tools.embedding is '工具語義向量';

comment on column mcp_tools.requires_approval is '是否需要審批';

comment on column mcp_tools.sensitivity is 'LOW, MEDIUM, HIGH';

alter table mcp_tools
    owner to yilena;

create index idx_mcp_tools_bean_method
    on mcp_tools (bean_name, method_name);

create index idx_mcp_tools_requires_approval
    on mcp_tools (requires_approval);

create index idx_mcp_tools_sensitivity
    on mcp_tools (sensitivity);

create index idx_mcp_tools_created_at
    on mcp_tools (created_at desc);

create index idx_mcp_tools_name_trgm
    on mcp_tools using gin (name gin_trgm_ops);

create index idx_mcp_tools_description_trgm
    on mcp_tools using gin (description gin_trgm_ops);
