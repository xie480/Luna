create table mcp_skills
(
    id                    bigint       not null
        primary key,
    name                  varchar(100) not null
        unique,
    description           text,
    version               varchar(20) default '1.0.0'::character varying,
    owner                 varchar(50),
    bean_name             varchar(100) not null,
    method_name           varchar(100) not null,
    input_schema          text,
    output_schema         text,
    run_mode              varchar(20) default 'SYNC'::character varying,
    required_capabilities jsonb       default '[]'::jsonb,
    tool_slots            jsonb       default '[]'::jsonb,
    thought_chain         jsonb       default '[]'::jsonb,
    embedding             vector(768),
    created_at            timestamp   default CURRENT_TIMESTAMP,
    updated_at            timestamp   default CURRENT_TIMESTAMP
);

comment on table mcp_skills is 'Legacy compatibility table, read-only mirror of historical skill definitions';

comment on column mcp_skills.id is '主鍵 ID (雪花算法)';

comment on column mcp_skills.name is '技能唯一名稱';

comment on column mcp_skills.description is '技能語義描述';

comment on column mcp_skills.bean_name is 'Legacy Spring Bean 名稱 (兼容字段，Host 不再读取)';

comment on column mcp_skills.method_name is 'Legacy 執行方法名稱 (兼容字段，Host 不再读取)';

comment on column mcp_skills.input_schema is '參數 JSON Schema';

comment on column mcp_skills.output_schema is '輸出 JSON Schema';

comment on column mcp_skills.run_mode is 'SYNC 或 ASYNC';

comment on column mcp_skills.required_capabilities is 'Skill 所需能力集合(JSON数组)';

comment on column mcp_skills.tool_slots is 'Skill 工具槽位定义(JSON数组对象)，用于 capability -> slot 绑定';

comment on column mcp_skills.thought_chain is 'Skill 的编排思维链(JSON字符串数组)，描述顺序、依赖与回退策略';

comment on column mcp_skills.embedding is '技能語義向量 (PGVector, 768維)';

alter table mcp_skills
    owner to yilena;

create index idx_mcp_skills_required_capabilities_gin
    on mcp_skills using gin (required_capabilities);

create index idx_mcp_skills_tool_slots_gin
    on mcp_skills using gin (tool_slots);

create index idx_mcp_skills_thought_chain_gin
    on mcp_skills using gin (thought_chain);
