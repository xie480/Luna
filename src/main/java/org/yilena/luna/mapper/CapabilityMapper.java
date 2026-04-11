package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
/**
 * 能力注册表 Mapper，负责把工具、提示词、资源和工作流等能力同步到统一能力注册表并执行检索。
 */
public interface CapabilityMapper {

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, embedding, updated_at
            )
            select
                'TOOL', t.server_code, concat(t.server_code, ':', t.tool_name), t.title, t.description,
                t.input_schema, t.output_schema,
                coalesce(t.raw_payload, '{}'::jsonb) || jsonb_build_object(
                    'invocation_name', t.tool_name,
                    'tool_name', t.tool_name,
                    'capability_key', concat(t.server_code, ':', t.tool_name),
                    'execution_mode', coalesce(t.execution_mode, 'MCP')
                ),
                coalesce(t.requires_approval, false), t.sensitivity,
                coalesce(t.enabled, true), coalesce(t.version, '1'), t.embedding, current_timestamp
            from mcp_tool_catalog t
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                input_schema = excluded.input_schema,
                output_schema = excluded.output_schema,
                metadata_json = excluded.metadata_json,
                requires_approval = excluded.requires_approval,
                sensitivity = excluded.sensitivity,
                enabled = excluded.enabled,
                version = excluded.version,
                embedding = excluded.embedding,
                updated_at = current_timestamp
            """)
    /**
     * 将工具目录同步到能力注册表。
     */
    int syncToolsIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, embedding, updated_at
            )
            select
                'PROMPT', p.server_code, concat(p.server_code, ':', p.prompt_name), p.title, p.description,
                p.arguments_schema, null,
                coalesce(p.raw_payload, '{}'::jsonb) || jsonb_build_object(
                    'invocation_name', p.prompt_name,
                    'prompt_name', p.prompt_name,
                    'capability_key', concat(p.server_code, ':', p.prompt_name)
                ),
                false, 'LOW',
                coalesce(p.enabled, true), coalesce(p.version, '1'), p.embedding, current_timestamp
            from mcp_prompt_catalog p
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                input_schema = excluded.input_schema,
                metadata_json = excluded.metadata_json,
                enabled = excluded.enabled,
                version = excluded.version,
                embedding = excluded.embedding,
                updated_at = current_timestamp
            """)
    /**
     * 将提示词目录同步到能力注册表。
     */
    int syncPromptsIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, embedding, updated_at
            )
            select
                'RESOURCE', r.server_code, concat(r.server_code, ':', r.resource_uri), r.name, r.description,
                null, null,
                coalesce(r.raw_payload, '{}'::jsonb) || jsonb_build_object(
                    'invocation_name', r.resource_uri,
                    'resource_uri', r.resource_uri,
                    'capability_key', concat(r.server_code, ':', r.resource_uri)
                ),
                false, 'LOW',
                coalesce(r.enabled, true), '1', r.embedding, current_timestamp
            from mcp_resource_catalog r
            union all
            select
                'RESOURCE', 'local-agent-server', 'local-agent-server:resource://knowledge/query', 'knowledge.query', 'Knowledge query template',
                null, null,
                jsonb_build_object('resource_uri', 'resource://knowledge/query', 'invocation_name', 'resource://knowledge/query', 'domain', 'knowledge'),
                false, 'LOW', true, '1', null, current_timestamp
            union all
            select
                'RESOURCE', 'local-agent-server', 'local-agent-server:resource://user/preferences/current', 'user.preferences.current', 'Current user preference snapshot template',
                null, null,
                jsonb_build_object('resource_uri', 'resource://user/preferences/current', 'invocation_name', 'resource://user/preferences/current', 'domain', 'user'),
                false, 'LOW', true, '1', null, current_timestamp
            union all
            select
                'RESOURCE', 'local-agent-server', 'local-agent-server:resource://memory/session/current', 'memory.session.current', 'Current memory snapshot template',
                null, null,
                jsonb_build_object('resource_uri', 'resource://memory/session/current', 'invocation_name', 'resource://memory/session/current', 'domain', 'memory'),
                false, 'LOW', true, '1', null, current_timestamp
            union all
            select
                'RESOURCE', 'local-agent-server', 'local-agent-server:resource://schedule/today', 'schedule.today', 'Today schedule template',
                null, null,
                jsonb_build_object('resource_uri', 'resource://schedule/today', 'invocation_name', 'resource://schedule/today', 'domain', 'schedule'),
                false, 'LOW', true, '1', null, current_timestamp
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                metadata_json = excluded.metadata_json,
                enabled = excluded.enabled,
                embedding = excluded.embedding,
                updated_at = current_timestamp
            """)
    /**
     * 将资源目录和内置资源模板同步到能力注册表。
     */
    int syncResourcesIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, embedding, updated_at
            )
            select
                'WORKFLOW', 'local-agent-server', concat('workflow:', w.workflow_name), w.workflow_name, w.description,
                w.input_schema, w.output_schema,
                coalesce(w.blueprint_json, '{}'::jsonb) || jsonb_build_object(
                    'invocation_name', w.workflow_name,
                    'workflow_name', w.workflow_name,
                    'capability_key', concat('workflow:', w.workflow_name)
                ),
                false, 'LOW',
                coalesce(w.enabled, true), coalesce(w.version, '1'), w.embedding, current_timestamp
            from workflow_template w
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                input_schema = excluded.input_schema,
                output_schema = excluded.output_schema,
                metadata_json = excluded.metadata_json,
                enabled = excluded.enabled,
                version = excluded.version,
                embedding = excluded.embedding,
                updated_at = current_timestamp
            """)
    /**
     * 将工作流模板同步到能力注册表。
     */
    int syncWorkflowsIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, embedding, updated_at
            )
            select
                'STRATEGY', 'task_procedure', concat('strategy:task:', p.name), p.name, p.description,
                p.trigger_conditions_json, p.pattern_steps_json,
                jsonb_build_object(
                    'procedure_type', p.procedure_type,
                    'source_kind', p.source_kind,
                    'confidence_score', p.confidence_score,
                    'usage_count', p.usage_count
                ),
                false, 'LOW', true, '1', p.embedding, current_timestamp
            from task_procedure_pattern p
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                input_schema = excluded.input_schema,
                output_schema = excluded.output_schema,
                metadata_json = excluded.metadata_json,
                enabled = excluded.enabled,
                embedding = excluded.embedding,
                updated_at = current_timestamp
            """)
    /**
     * 将任务策略模式同步到能力注册表。
     */
    int syncTaskStrategiesIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, embedding, updated_at
            )
            select
                'STRATEGY', 'relation_procedure', concat('strategy:relation:', p.name), p.name, p.description,
                p.trigger_conditions_json, p.pattern_steps_json,
                jsonb_build_object(
                    'procedure_type', p.procedure_type,
                    'source_kind', p.source_kind,
                    'confidence_score', p.confidence_score,
                    'usage_count', p.usage_count
                ),
                false, 'LOW', true, '1', p.embedding, current_timestamp
            from relational_procedure_pattern p
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                input_schema = excluded.input_schema,
                output_schema = excluded.output_schema,
                metadata_json = excluded.metadata_json,
                enabled = excluded.enabled,
                embedding = excluded.embedding,
                updated_at = current_timestamp
            """)
    /**
     * 将关系策略模式同步到能力注册表。
     */
    int syncRelationalStrategiesIntoRegistry();

    @Select("""
            select capability_id, capability_type, capability_name, server_code, title, description,
                   input_schema, output_schema, metadata_json, requires_approval, sensitivity, version
            from capability_registry
            where enabled = true
            order by capability_type asc, capability_name asc
            limit 24
            """)
    /**
     * 查询已启用的高优先级能力列表。
     */
    List<Map<String, Object>> selectTopCapabilities();

    @Select("""
            select capability_id, capability_type, capability_name, server_code, title, description,
                   input_schema, output_schema, metadata_json, requires_approval, sensitivity, version
            from capability_registry
            where enabled = true
              and (
                    lower(capability_name) like concat('%', lower(#{query}), '%')
                 or lower(coalesce(title, '')) like concat('%', lower(#{query}), '%')
                 or lower(coalesce(description, '')) like concat('%', lower(#{query}), '%')
              )
            order by updated_at desc, capability_name asc
            limit #{limit}
            """)
    /**
     * 按关键字搜索能力候选项。
     */
    List<Map<String, Object>> searchCapabilityCandidates(@Param("query") String query, @Param("limit") int limit);

    @Select("""
            select capability_id, capability_type, capability_name, server_code, title, description,
                   input_schema, output_schema, metadata_json, requires_approval, sensitivity, version
            from capability_registry
            where enabled = true
              and embedding is not null
            order by embedding::vector <-> #{vector}::vector
            limit #{limit}
            """)
    /**
     * 按向量相似度搜索能力候选项。
     */
    List<Map<String, Object>> searchCapabilityCandidatesByVector(@Param("vector") String vector, @Param("limit") int limit);
}
