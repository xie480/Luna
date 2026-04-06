package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface McpLegacyMigrationMapper {

    @Select("""
            select id, name, description, version, owner, bean_name, method_name,
                   input_schema, output_schema, embedding, requires_approval, sensitivity
            from mcp_tools
            """)
    List<Map<String, Object>> selectLegacyTools();

    @Select("""
            select id, name, description, version, owner, bean_name, method_name,
                   input_schema, output_schema, run_mode,
                   required_capabilities, tool_slots, thought_chain, embedding
            from mcp_skills
            """)
    List<Map<String, Object>> selectLegacySkills();

    @Select("""
            select server_code, tool_name
            from mcp_tool_catalog
            where lower(tool_name) in ('manage_memory','manage_schedule_task','manage_knowledge_base','manage_log',
                                       'web_search','image_search','news_search','lens_search','web_scrape')
            """)
    List<Map<String, Object>> selectCoreLocalHandlerTools();

    @Select("select to_regclass(#{tableName})")
    String selectRegclass(@Param("tableName") String tableName);

    @Select("select count(1) from mcp_tools")
    Long countMcpTools();

    @Select("select count(1) from mcp_skills")
    Long countMcpSkills();

    @Select("select count(1) from mcp_tool_catalog")
    Long countMcpToolCatalog();

    @Select("select count(1) from mcp_tool_impl_mapping")
    Long countMcpToolImplMapping();

    @Select("select count(1) from mcp_prompt_catalog")
    Long countMcpPromptCatalog();

    @Select("select count(1) from workflow_template")
    Long countWorkflowTemplate();
}
