-- Luna dual-domain memory runtime schema (v2)
-- Source of truth: docs/memory.md

create extension if not exists vector;
create extension if not exists pg_trgm;

create table if not exists principal (
    principal_id bigserial primary key,
    principal_type varchar(32) not null default 'USER',
    tenant_id varchar(128),
    display_name varchar(255),
    profile_json jsonb,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists agent_identity (
    agent_id bigserial primary key,
    agent_name varchar(128) not null,
    persona_name varchar(128),
    persona_desc text,
    default_tone varchar(64),
    config_json jsonb,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists agent_session (
    session_id varchar(128) primary key,
    principal_id bigint,
    agent_id bigint,
    session_type varchar(16) not null default 'HYBRID',
    task_state varchar(64) not null default 'IDLE',
    relational_state varchar(64) not null default 'COLD_START',
    current_plan_id bigint,
    current_goal text,
    last_user_message_at timestamp,
    last_agent_message_at timestamp,
    metadata_json jsonb,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_agent_session_type check (session_type in ('TASK', 'COMPANION', 'HYBRID'))
);

create table if not exists state_transition_log (
    id bigserial primary key,
    session_id varchar(128) not null,
    state_domain varchar(16) not null,
    from_state varchar(64),
    to_state varchar(64) not null,
    trigger_type varchar(64),
    trigger_ref varchar(255),
    reason text,
    payload_json jsonb,
    created_at timestamp not null default current_timestamp,
    constraint ck_state_domain check (state_domain in ('TASK', 'RELATION'))
);

create table if not exists conversation_message (
    message_id bigserial primary key,
    session_id varchar(128) not null,
    plan_id bigint,
    role varchar(32) not null,
    message_type varchar(32) not null default 'TEXT',
    content_text text,
    content_json jsonb,
    trace_id varchar(128),
    created_at timestamp not null default current_timestamp
);

create table if not exists event_inbox (
    event_id bigserial primary key,
    session_id varchar(128) not null,
    event_type varchar(32) not null,
    payload_json jsonb,
    status varchar(32) not null default 'PENDING',
    trace_id varchar(128),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_event_type check (event_type in ('USER_INPUT', 'TOOL_RESULT', 'APPROVAL', 'SYSTEM', 'TIMER'))
);

create table if not exists task_working_memory (
    twm_id bigserial primary key,
    session_id varchar(128) not null,
    principal_id bigint,
    plan_id bigint,
    goal_raw text,
    goal_refined text,
    intent_json jsonb,
    constraints_json jsonb,
    success_criteria_json jsonb,
    assumptions_json jsonb,
    key_entities_json jsonb,
    key_facts_json jsonb,
    unresolved_questions_json jsonb,
    risks_json jsonb,
    active_phase_id bigint,
    active_node_id bigint,
    recent_tool_outputs_json jsonb,
    local_scratchpad text,
    version integer not null default 1,
    updated_at timestamp not null default current_timestamp
);

create table if not exists task_working_memory_slot (
    id bigserial primary key,
    twm_id bigint not null,
    slot_name varchar(128) not null,
    slot_type varchar(64),
    slot_value_json jsonb,
    priority integer not null default 50,
    freshness_score numeric(5, 4) not null default 1.0,
    source_type varchar(64),
    source_ref varchar(255),
    updated_at timestamp not null default current_timestamp
);

create table if not exists task_semantic_fact (
    fact_id bigserial primary key,
    principal_id bigint,
    scope_type varchar(16) not null,
    fact_type varchar(32) not null,
    fact_key varchar(255) not null,
    fact_value_text text,
    fact_value_json jsonb,
    description text,
    confidence_score numeric(5, 4) not null default 0.5,
    stability_score numeric(5, 4) not null default 0.5,
    source_type varchar(64),
    source_ref varchar(255),
    valid_from timestamp,
    valid_to timestamp,
    last_confirmed_at timestamp,
    embedding vector(768),
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_task_scope_type check (scope_type in ('USER', 'SESSION', 'PLAN', 'GLOBAL')),
    constraint ck_task_fact_type check (fact_type in ('PREFERENCE', 'PROFILE', 'RULE', 'CONSTRAINT', 'DOMAIN_FACT'))
);

create table if not exists knowledge_document (
    doc_id bigserial primary key,
    owner_scope varchar(32),
    owner_ref varchar(255),
    source_type varchar(64),
    source_uri text,
    title varchar(500),
    metadata_json jsonb,
    created_at timestamp not null default current_timestamp
);

create table if not exists knowledge_chunk (
    chunk_id bigserial primary key,
    doc_id bigint not null,
    chunk_order integer not null,
    chunk_text text,
    chunk_summary text,
    keywords_json jsonb,
    embedding vector(768),
    tsv tsvector,
    metadata_json jsonb,
    created_at timestamp not null default current_timestamp
);

create table if not exists task_episode (
    episode_id bigserial primary key,
    principal_id bigint,
    session_id varchar(128),
    plan_id bigint,
    episode_type varchar(16) not null,
    title varchar(255),
    task_goal text,
    context_json jsonb,
    trajectory_summary text,
    outcome_summary text,
    outcome_status varchar(32),
    lessons_learned text,
    importance_score numeric(5, 4) not null default 0.5,
    reusability_score numeric(5, 4) not null default 0.5,
    embedding vector(768),
    created_at timestamp not null default current_timestamp,
    constraint ck_task_episode_type check (episode_type in ('SUCCESS', 'FAILURE', 'DECISION', 'PARTIAL'))
);

create table if not exists task_episode_step (
    id bigserial primary key,
    episode_id bigint not null,
    step_order integer not null,
    step_type varchar(64),
    title varchar(255),
    content_text text,
    payload_json jsonb,
    created_at timestamp not null default current_timestamp
);

create table if not exists task_procedure_pattern (
    procedure_id bigserial primary key,
    procedure_type varchar(32) not null,
    name varchar(255) not null,
    description text,
    trigger_conditions_json jsonb,
    applicability_scope_json jsonb,
    pattern_steps_json jsonb,
    success_signals_json jsonb,
    failure_signals_json jsonb,
    source_kind varchar(64),
    confidence_score numeric(5, 4) not null default 0.5,
    usage_count integer not null default 0,
    success_count integer not null default 0,
    fail_count integer not null default 0,
    embedding vector(768),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_task_procedure_type check (procedure_type in ('PLANNING_PATTERN', 'TOOL_CHAIN', 'RECOVERY', 'VALIDATION'))
);

create table if not exists task_reflection_record (
    reflection_id bigserial primary key,
    plan_id bigint,
    node_id bigint,
    reflection_type varchar(64),
    trigger_reason text,
    observation text,
    root_cause text,
    proposed_fix text,
    extracted_pattern_json jsonb,
    quality_score numeric(5, 4),
    created_at timestamp not null default current_timestamp
);

create table if not exists relational_working_memory (
    rwm_id bigserial primary key,
    session_id varchar(128) not null,
    principal_id bigint,
    current_relational_state varchar(64),
    inferred_emotion varchar(64),
    emotion_confidence numeric(5, 4),
    desired_tone varchar(64),
    support_intent varchar(128),
    interaction_goal text,
    caution_flags_json jsonb,
    recent_bond_signals_json jsonb,
    recent_sensitive_signals_json jsonb,
    updated_at timestamp not null default current_timestamp
);

create table if not exists relational_profile (
    profile_id bigserial primary key,
    principal_id bigint not null,
    relationship_stage varchar(64),
    preferred_name varchar(128),
    preferred_tone varchar(64),
    emotional_support_style varchar(64),
    humor_preference varchar(64),
    intimacy_preference varchar(64),
    interaction_style_json jsonb,
    boundary_preferences_json jsonb,
    sensitive_topics_json jsonb,
    comfort_triggers_json jsonb,
    no_go_patterns_json jsonb,
    trust_score numeric(5, 4) not null default 0.5,
    intimacy_score numeric(5, 4) not null default 0.5,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists relational_semantic_fact (
    fact_id bigserial primary key,
    principal_id bigint,
    fact_type varchar(32) not null,
    fact_key varchar(255) not null,
    fact_value_text text,
    fact_value_json jsonb,
    description text,
    confidence_score numeric(5, 4) not null default 0.5,
    stability_score numeric(5, 4) not null default 0.5,
    source_type varchar(64),
    source_ref varchar(255),
    valid_from timestamp,
    valid_to timestamp,
    last_confirmed_at timestamp,
    embedding vector(768),
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_rel_fact_type check (fact_type in ('ADDRESS_PREFERENCE', 'SUPPORT_STYLE', 'BOUNDARY', 'SENSITIVE_TOPIC', 'INTERACTION_STYLE'))
);

create table if not exists emotional_baseline (
    id bigserial primary key,
    principal_id bigint not null,
    usual_expression_style varchar(128),
    stress_signals_json jsonb,
    burnout_signals_json jsonb,
    sadness_signals_json jsonb,
    comfort_preferences_json jsonb,
    encouragement_patterns_json jsonb,
    escalation_threshold numeric(5, 4) not null default 0.7,
    updated_at timestamp not null default current_timestamp
);

create table if not exists relational_episode (
    episode_id bigserial primary key,
    principal_id bigint,
    session_id varchar(128),
    episode_type varchar(16) not null,
    title varchar(255),
    summary text,
    emotion_before varchar(64),
    emotion_after varchar(64),
    trigger_json jsonb,
    support_style_used varchar(128),
    interaction_quality numeric(5, 4),
    response_effectiveness numeric(5, 4),
    embedding vector(768),
    created_at timestamp not null default current_timestamp,
    constraint ck_rel_episode_type check (episode_type in ('COMFORT', 'BONDING', 'REPAIR', 'CELEBRATION', 'DISCLOSURE'))
);

create table if not exists relational_procedure_pattern (
    procedure_id bigserial primary key,
    procedure_type varchar(32) not null,
    name varchar(255) not null,
    description text,
    trigger_conditions_json jsonb,
    applicability_scope_json jsonb,
    pattern_steps_json jsonb,
    success_signals_json jsonb,
    failure_signals_json jsonb,
    source_kind varchar(64),
    confidence_score numeric(5, 4) not null default 0.5,
    usage_count integer not null default 0,
    success_count integer not null default 0,
    fail_count integer not null default 0,
    embedding vector(768),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_rel_procedure_type check (procedure_type in ('COMFORT_PATTERN', 'REPAIR_PATTERN', 'LIGHT_CHAT_PATTERN', 'TRANSITION_PATTERN'))
);

create table if not exists relational_reflection_record (
    reflection_id bigserial primary key,
    session_id varchar(128),
    reflection_type varchar(32) not null,
    trigger_reason text,
    observation text,
    root_cause text,
    proposed_fix text,
    extracted_pattern_json jsonb,
    quality_score numeric(5, 4),
    created_at timestamp not null default current_timestamp,
    constraint ck_rel_reflection_type check (reflection_type in ('SUPPORT_REVIEW', 'MISALIGNMENT', 'REPAIR_ANALYSIS'))
);

create table if not exists relational_boundary_rule (
    id bigserial primary key,
    principal_id bigint not null,
    rule_type varchar(16) not null,
    rule_key varchar(255) not null,
    rule_value text,
    confidence_score numeric(5, 4) not null default 0.5,
    source_type varchar(64),
    updated_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    constraint ck_rel_boundary_type check (rule_type in ('ADDRESS', 'PRIVACY', 'TOPIC', 'EMOTIONAL', 'PACE'))
);

create table if not exists memory_registry (
    memory_id bigserial primary key,
    memory_domain varchar(16) not null,
    memory_layer varchar(16) not null,
    ref_table varchar(128) not null,
    ref_id varchar(128) not null,
    principal_id bigint,
    source_type varchar(64),
    source_ref varchar(255),
    confidence_score numeric(5, 4) not null default 0.5,
    importance_score numeric(5, 4) not null default 0.5,
    freshness_score numeric(5, 4) not null default 1.0,
    access_count integer not null default 0,
    last_accessed_at timestamp,
    archived boolean not null default false,
    created_at timestamp not null default current_timestamp,
    constraint ck_memory_domain check (memory_domain in ('TASK', 'RELATION')),
    constraint ck_memory_layer check (memory_layer in ('WORKING', 'SEMANTIC', 'EPISODIC', 'PROCEDURAL'))
);

create table if not exists memory_relation (
    id bigserial primary key,
    from_memory_id bigint not null,
    to_memory_id bigint not null,
    relation_type varchar(16) not null,
    weight numeric(5, 4) not null default 1.0,
    created_at timestamp not null default current_timestamp,
    constraint ck_memory_relation_type check (relation_type in ('SUPPORTS', 'CONTRADICTS', 'DERIVED_FROM', 'SUMMARIZES', 'GENERALIZES'))
);

create table if not exists capability_registry (
    capability_id bigserial primary key,
    capability_type varchar(16) not null,
    server_code varchar(128),
    capability_name varchar(128) not null,
    title varchar(255),
    description text,
    input_schema jsonb,
    output_schema jsonb,
    metadata_json jsonb,
    requires_approval boolean not null default false,
    sensitivity varchar(32),
    enabled boolean not null default true,
    version varchar(32),
    embedding vector(768),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_capability_type check (capability_type in ('TOOL', 'WORKFLOW', 'PROMPT', 'RESOURCE', 'STRATEGY'))
);

create table if not exists plan_context_snapshot (
    id bigserial primary key,
    plan_id bigint not null,
    node_id bigint,
    session_id varchar(128),
    context_package_json jsonb not null,
    created_at timestamp not null default current_timestamp
);

create table if not exists plan_decision_record (
    id bigserial primary key,
    plan_id bigint not null,
    node_id bigint,
    decision_type varchar(64) not null,
    decision_reason text,
    decision_payload jsonb,
    created_at timestamp not null default current_timestamp
);

create table if not exists tool_execution_trace (
    id bigserial primary key,
    plan_id bigint,
    node_id bigint,
    session_id varchar(128),
    tool_name varchar(128) not null,
    call_status varchar(32) not null,
    normalized_input jsonb,
    normalized_output jsonb,
    error_message text,
    latency_ms bigint,
    created_at timestamp not null default current_timestamp
);

create unique index if not exists uk_task_working_memory_session on task_working_memory(session_id);
create unique index if not exists uk_task_working_memory_slot on task_working_memory_slot(twm_id, slot_name);
create unique index if not exists uk_relational_working_memory_session on relational_working_memory(session_id);
create unique index if not exists uk_relational_profile_principal on relational_profile(principal_id);
create unique index if not exists uk_emotional_baseline_principal on emotional_baseline(principal_id);
create unique index if not exists uk_memory_registry_ref on memory_registry(ref_table, ref_id);
create unique index if not exists uk_capability_registry_name on capability_registry(capability_name);

create index if not exists idx_agent_session_state on agent_session(task_state, relational_state);
create index if not exists idx_state_transition_log_session on state_transition_log(session_id, created_at desc);
create index if not exists idx_conversation_message_session on conversation_message(session_id, created_at desc);
create index if not exists idx_event_inbox_session_status on event_inbox(session_id, status, created_at desc);
create index if not exists idx_task_semantic_fact_embedding on task_semantic_fact using ivfflat (embedding vector_cosine_ops);
create index if not exists idx_task_episode_embedding on task_episode using ivfflat (embedding vector_cosine_ops);
create index if not exists idx_task_procedure_embedding on task_procedure_pattern using ivfflat (embedding vector_cosine_ops);
create index if not exists idx_relational_episode_embedding on relational_episode using ivfflat (embedding vector_cosine_ops);
create index if not exists idx_relational_procedure_embedding on relational_procedure_pattern using ivfflat (embedding vector_cosine_ops);
create index if not exists idx_knowledge_chunk_embedding on knowledge_chunk using ivfflat (embedding vector_cosine_ops);
create index if not exists idx_knowledge_chunk_tsv on knowledge_chunk using gin(tsv);
create index if not exists idx_plan_context_snapshot_plan on plan_context_snapshot(plan_id, created_at desc);
create index if not exists idx_plan_decision_record_plan on plan_decision_record(plan_id, created_at desc);
create index if not exists idx_tool_execution_trace_plan on tool_execution_trace(plan_id, created_at desc);
