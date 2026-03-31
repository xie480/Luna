package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface CapabilityMapper {

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, updated_at
            )
            select
                'TOOL', t.server_code, concat(t.server_code, ':', t.tool_name), t.title, t.description,
                t.input_schema, t.output_schema, t.raw_payload, coalesce(t.requires_approval, false), t.sensitivity,
                coalesce(t.enabled, true), coalesce(t.version, '1'), current_timestamp
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
                updated_at = current_timestamp
            """)
    int syncToolsIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, updated_at
            )
            select
                'PROMPT', p.server_code, concat(p.server_code, ':', p.prompt_name), p.title, p.description,
                p.arguments_schema, null, p.raw_payload, false, 'LOW',
                coalesce(p.enabled, true), coalesce(p.version, '1'), current_timestamp
            from mcp_prompt_catalog p
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                input_schema = excluded.input_schema,
                metadata_json = excluded.metadata_json,
                enabled = excluded.enabled,
                version = excluded.version,
                updated_at = current_timestamp
            """)
    int syncPromptsIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, updated_at
            )
            select
                'RESOURCE', r.server_code, concat(r.server_code, ':', r.resource_uri), r.name, r.description,
                null, null, r.raw_payload, false, 'LOW',
                coalesce(r.enabled, true), '1', current_timestamp
            from mcp_resource_catalog r
            on conflict (capability_name)
            do update set
                title = excluded.title,
                description = excluded.description,
                metadata_json = excluded.metadata_json,
                enabled = excluded.enabled,
                updated_at = current_timestamp
            """)
    int syncResourcesIntoRegistry();

    @Update("""
            insert into capability_registry(
                capability_type, server_code, capability_name, title, description,
                input_schema, output_schema, metadata_json, requires_approval, sensitivity,
                enabled, version, updated_at
            )
            select
                'WORKFLOW', 'workflow', concat('workflow:', w.workflow_name), w.workflow_name, w.description,
                w.input_schema, w.output_schema, w.blueprint_json, false, 'LOW',
                coalesce(w.enabled, true), coalesce(w.version, '1'), current_timestamp
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
                updated_at = current_timestamp
            """)
    int syncWorkflowsIntoRegistry();

    @Select("""
            select capability_id, capability_type, capability_name, title, description, requires_approval, sensitivity
            from capability_registry
            where enabled = true
            order by capability_type asc, capability_name asc
            limit 24
            """)
    List<Map<String, Object>> selectTopCapabilities();

    @Select("""
            select capability_id, capability_type, capability_name, server_code, title, description,
                   input_schema, output_schema, requires_approval, sensitivity, version
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
    List<Map<String, Object>> searchCapabilityCandidates(@Param("query") String query, @Param("limit") int limit);
}
