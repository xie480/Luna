create table workflow_template
(
    id                    bigint       not null
        primary key,
    workflow_name         varchar(200) not null
        unique,
    description           text,
    input_schema          jsonb,
    output_schema         jsonb,
    required_capabilities jsonb     default '[]'::jsonb,
    tool_slots            jsonb     default '[]'::jsonb,
    thought_chain         jsonb     default '[]'::jsonb,
    blueprint_json        jsonb,
    enabled               boolean   default true,
    version               varchar(50),
    embedding             vector(768),
    created_at            timestamp default CURRENT_TIMESTAMP,
    updated_at            timestamp default CURRENT_TIMESTAMP
);

comment on table workflow_template is 'Workflow templates split from legacy skills';

alter table workflow_template
    owner to yilena;

create index idx_workflow_template_enabled
    on workflow_template (enabled);

create index idx_workflow_template_required_capabilities_gin
    on workflow_template using gin (required_capabilities);

create index idx_workflow_template_tool_slots_gin
    on workflow_template using gin (tool_slots);

create index idx_workflow_template_thought_chain_gin
    on workflow_template using gin (thought_chain);
