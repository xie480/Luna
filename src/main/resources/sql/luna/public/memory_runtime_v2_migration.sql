-- Luna dual-domain memory migration (legacy -> v2)
-- Run after memory_runtime_v2_schema.sql

insert into principal (principal_id, principal_type, display_name, created_at, updated_at)
select distinct cast(abs(hashtext(session_id)) as bigint), 'USER', session_id, current_timestamp, current_timestamp
from luna_memory
where session_id is not null
on conflict (principal_id) do nothing;

insert into task_semantic_fact (
    principal_id, scope_type, fact_type, fact_key, fact_value_text, description,
    confidence_score, stability_score, source_type, source_ref, embedding, deleted, created_at, updated_at
)
select
    null,
    'GLOBAL',
    'PREFERENCE',
    pref_key,
    pref_value,
    description,
    0.8,
    0.8,
    'LEGACY_USER_PREFERENCE',
    cast(id as varchar),
    embedding,
    coalesce(deleted, 0) <> 0,
    created_at,
    updated_at
from user_preference up
where not exists (
    select 1 from task_semantic_fact tsf
    where tsf.source_type = 'LEGACY_USER_PREFERENCE' and tsf.source_ref = cast(up.id as varchar)
);

insert into relational_semantic_fact (
    principal_id, fact_type, fact_key, fact_value_text, description,
    confidence_score, stability_score, source_type, source_ref, embedding, deleted, created_at, updated_at
)
select
    null,
    'INTERACTION_STYLE',
    pref_key,
    pref_value,
    description,
    0.7,
    0.7,
    'LEGACY_USER_PREFERENCE',
    cast(id as varchar),
    embedding,
    coalesce(deleted, 0) <> 0,
    created_at,
    updated_at
from user_preference up
where not exists (
    select 1 from relational_semantic_fact rsf
    where rsf.source_type = 'LEGACY_USER_PREFERENCE' and rsf.source_ref = cast(up.id as varchar)
);

insert into task_episode (
    principal_id, session_id, episode_type, title, trajectory_summary,
    importance_score, reusability_score, embedding, created_at
)
select
    cast(abs(hashtext(session_id)) as bigint),
    session_id,
    case memory_type when 3 then 'DECISION' else 'PARTIAL' end,
    left(content, 120),
    content,
    least(greatest(coalesce(weight, 1) / 10.0, 0.1), 1.0),
    0.5,
    embedding,
    created_at
from luna_memory lm
where not exists (
    select 1 from task_episode te
    where te.session_id = lm.session_id and te.created_at = lm.created_at and te.trajectory_summary = lm.content
);

insert into task_semantic_fact (
    principal_id, scope_type, fact_type, fact_key, fact_value_text,
    confidence_score, stability_score, source_type, source_ref,
    embedding, deleted, created_at, updated_at
)
select
    cast(abs(hashtext(session_id)) as bigint),
    'SESSION',
    case memory_type when 1 then 'PREFERENCE' when 0 then 'DOMAIN_FACT' else 'RULE' end,
    'legacy_memory_' || cast(id as varchar),
    content,
    least(greatest(coalesce(weight, 1) / 10.0, 0.1), 1.0),
    0.6,
    'LEGACY_LUNA_MEMORY',
    cast(id as varchar),
    embedding,
    false,
    created_at,
    updated_at
from luna_memory lm
where not exists (
    select 1 from task_semantic_fact tsf
    where tsf.source_type = 'LEGACY_LUNA_MEMORY' and tsf.source_ref = cast(lm.id as varchar)
);

insert into knowledge_document (doc_id, owner_scope, owner_ref, source_type, source_uri, title, metadata_json, created_at)
select
    id,
    'GLOBAL',
    null,
    cast(source_type as varchar),
    source_path,
    title,
    jsonb_build_object('legacy_vector_id', vector_id),
    created_at
from knowledge_base kb
where not exists (select 1 from knowledge_document kd where kd.doc_id = kb.id);

insert into knowledge_chunk (
    doc_id, chunk_order, chunk_text, chunk_summary, keywords_json, embedding, tsv, metadata_json, created_at
)
select
    id,
    1,
    content,
    left(content, 200),
    '[]'::jsonb,
    embedding,
    to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')),
    jsonb_build_object('legacy_source', 'knowledge_base'),
    created_at
from knowledge_base kb
where not exists (select 1 from knowledge_chunk kc where kc.doc_id = kb.id and kc.chunk_order = 1);

insert into memory_registry (
    memory_domain, memory_layer, ref_table, ref_id, principal_id,
    source_type, source_ref, confidence_score, importance_score, freshness_score, created_at
)
select
    'TASK', 'SEMANTIC', 'task_semantic_fact', cast(fact_id as varchar), principal_id,
    source_type, source_ref, confidence_score, 0.6, 1.0, created_at
from task_semantic_fact tsf
where not exists (
    select 1 from memory_registry mr where mr.ref_table = 'task_semantic_fact' and mr.ref_id = cast(tsf.fact_id as varchar)
);

insert into memory_registry (
    memory_domain, memory_layer, ref_table, ref_id, principal_id,
    source_type, source_ref, confidence_score, importance_score, freshness_score, created_at
)
select
    'RELATION', 'SEMANTIC', 'relational_semantic_fact', cast(fact_id as varchar), principal_id,
    source_type, source_ref, confidence_score, 0.6, 1.0, created_at
from relational_semantic_fact rsf
where not exists (
    select 1 from memory_registry mr where mr.ref_table = 'relational_semantic_fact' and mr.ref_id = cast(rsf.fact_id as varchar)
);

create or replace view luna_memory_v2_compat as
select
    tsf.fact_id as id,
    null::varchar as session_id,
    0::smallint as memory_type,
    tsf.fact_value_text as content,
    cast(round(tsf.confidence_score * 10) as integer) as weight,
    tsf.created_at,
    tsf.updated_at,
    tsf.embedding
from task_semantic_fact tsf
where tsf.deleted = false;

create or replace view user_preference_v2_compat as
select
    tsf.fact_id as id,
    tsf.fact_key as pref_key,
    tsf.fact_value_text as pref_value,
    tsf.description,
    tsf.created_at,
    tsf.updated_at,
    tsf.embedding,
    case when tsf.deleted then 1 else 0 end as deleted
from task_semantic_fact tsf
where tsf.fact_type = 'PREFERENCE';

create or replace view knowledge_base_v2_compat as
select
    kc.chunk_id as id,
    kd.title,
    kc.chunk_text as content,
    null::smallint as source_type,
    kd.source_uri as source_path,
    null::varchar as vector_id,
    kc.created_at,
    kc.created_at as updated_at,
    kc.embedding
from knowledge_chunk kc
join knowledge_document kd on kd.doc_id = kc.doc_id;
