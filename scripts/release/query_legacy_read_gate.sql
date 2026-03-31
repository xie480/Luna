-- Collect read traffic by release version for legacy tables.
-- Use output to update docs/release/legacy-read-gate.json.

select
    app_version as version,
    sum(case when legacy_table = 'mcp_tools' then read_count else 0 end) as mcp_tools_read_count,
    sum(case when legacy_table = 'mcp_skills' then read_count else 0 end) as mcp_skills_read_count,
    max(last_seen_at) as last_seen_at
from legacy_mcp_read_metric
where legacy_table in ('mcp_tools', 'mcp_skills')
group by app_version
order by max(last_seen_at) desc;

