BEGIN;

-- 1) Ensure target servers exist
INSERT INTO mcp_server_registry (server_code, server_name, description, base_url, transport_type, enabled)
VALUES
('code-ops-server', 'Code Ops MCP Server', 'Build, test, patch, git, and source operations', 'http://localhost:8080/mcp', 'HTTP', TRUE),
('plan-execution-server', 'Plan Execution MCP Server', 'Plan execution, node status, checkpoints, reports, and events', 'http://localhost:8080/mcp', 'HTTP', TRUE),
('search-intel-server', 'Search Intel MCP Server', 'Web/news/image/lens search and scraping', 'http://localhost:8080/mcp', 'HTTP', TRUE),
('knowledge-memory-server', 'Knowledge and Memory MCP Server', 'Knowledge base, memory, preference, and schedule operations', 'http://localhost:8080/mcp', 'HTTP', TRUE),
('desktop-automation-server', 'Desktop Automation MCP Server', 'Desktop screenshot and UI detection tools', 'http://localhost:8080/mcp', 'HTTP', TRUE)
ON CONFLICT (server_code) DO UPDATE SET
  server_name = EXCLUDED.server_name,
  description = EXCLUDED.description,
  base_url = EXCLUDED.base_url,
  transport_type = EXCLUDED.transport_type,
  enabled = EXCLUDED.enabled,
  updated_at = CURRENT_TIMESTAMP;

-- 2) Build tool->server mapping temp table
CREATE TEMP TABLE tmp_tool_server_assign(tool_name varchar(200), server_code varchar(100));
INSERT INTO tmp_tool_server_assign(tool_name, server_code)
VALUES
('acquire_execution_lock', 'plan-execution-server'),
('append_node_output', 'plan-execution-server'),
('apply_unified_patch', 'code-ops-server'),
('capture_desktop_screenshot', 'desktop-automation-server'),
('checkpoint_plan_state', 'plan-execution-server'),
('collect_test_report', 'code-ops-server'),
('detect_ui_elements', 'desktop-automation-server'),
('emit_plan_event_sse', 'plan-execution-server'),
('git_create_checkpoint', 'code-ops-server'),
('git_rollback_checkpoint', 'code-ops-server'),
('image_search', 'search-intel-server'),
('lens_search', 'search-intel-server'),
('list_phase_nodes', 'plan-execution-server'),
('load_plan_blueprint', 'plan-execution-server'),
('manage_knowledge_base', 'knowledge-memory-server'),
('manage_log', 'knowledge-memory-server'),
('manage_memory', 'knowledge-memory-server'),
('manage_schedule_task', 'knowledge-memory-server'),
('manage_user_preference', 'knowledge-memory-server'),
('news_search', 'search-intel-server'),
('open_browser_with_file', 'plan-execution-server'),
('query_plan_progress', 'plan-execution-server'),
('read_repo_tree', 'code-ops-server'),
('read_source_file', 'code-ops-server'),
('record_plan_audit_log', 'plan-execution-server'),
('release_execution_lock', 'plan-execution-server'),
('run_build_command', 'code-ops-server'),
('run_format_command', 'code-ops-server'),
('run_lint_command', 'code-ops-server'),
('run_test_command', 'code-ops-server'),
('save_plan_blueprint', 'plan-execution-server'),
('scan_dependency_vulnerabilities', 'code-ops-server'),
('search_symbol_references', 'code-ops-server'),
('update_node_status', 'plan-execution-server'),
('web_scrape', 'search-intel-server'),
('web_search', 'search-intel-server'),
('write_html_report_file', 'plan-execution-server'),
('write_source_file', 'code-ops-server');

-- 3) Reassign tool catalog
UPDATE mcp_tool_catalog c
SET server_code = m.server_code, updated_at = CURRENT_TIMESTAMP
FROM tmp_tool_server_assign m
WHERE c.tool_name = m.tool_name
  AND c.server_code <> m.server_code;

-- 4) Reassign tool impl mapping
UPDATE mcp_tool_impl_mapping i
SET server_code = m.server_code, updated_at = CURRENT_TIMESTAMP
FROM tmp_tool_server_assign m
WHERE i.tool_name = m.tool_name
  AND i.server_code <> m.server_code;

-- 5) Best-effort prompt reassignment by legacy bean mapping
WITH bean_server AS (
  SELECT DISTINCT i.bean_name, i.server_code
  FROM mcp_tool_impl_mapping i
  WHERE i.bean_name IS NOT NULL
)
UPDATE mcp_prompt_catalog p
SET server_code = b.server_code, updated_at = CURRENT_TIMESTAMP
FROM bean_server b
WHERE p.raw_payload IS NOT NULL
  AND p.raw_payload ? 'legacyBeanName'
  AND p.raw_payload->>'legacyBeanName' = b.bean_name
  AND p.server_code <> b.server_code;

-- 6) Report coverage
SELECT
  (SELECT COUNT(*) FROM tmp_tool_server_assign) AS assigned_tools,
  (SELECT COUNT(*) FROM mcp_tool_catalog c JOIN tmp_tool_server_assign m ON c.tool_name = m.tool_name AND c.server_code = m.server_code) AS tool_catalog_matched,
  (SELECT COUNT(*) FROM mcp_tool_impl_mapping i JOIN tmp_tool_server_assign m ON i.tool_name = m.tool_name AND i.server_code = m.server_code) AS impl_mapping_matched;

COMMIT;
