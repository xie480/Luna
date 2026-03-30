#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate (and optionally apply) SQL that reassigns MCP tools across servers.

Updates:
- mcp_server_registry (upsert server definitions)
- mcp_tool_catalog.server_code
- mcp_tool_impl_mapping.server_code
- mcp_prompt_catalog.server_code (best effort via raw_payload.legacyBeanName -> bean_name)
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from typing import Any, Dict, List, Set


def load_json(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError("source file must be a JSON object")
    return data


def sql_quote(value: Any) -> str:
    if value is None:
        return "NULL"
    text = str(value).replace("'", "''")
    return f"'{text}'"


def build_sql(source: Dict[str, Any]) -> str:
    servers = source.get("servers") or []
    assignments = source.get("tool_assignments") or []
    if not isinstance(servers, list) or not isinstance(assignments, list):
        raise ValueError("servers/tool_assignments must be arrays")

    seen_tools: Set[str] = set()
    seen_servers: Set[str] = set()
    for row in assignments:
        if not isinstance(row, dict):
            raise ValueError("tool_assignments items must be objects")
        tool = str(row.get("tool_name") or "").strip()
        server = str(row.get("server_code") or "").strip()
        if not tool or not server:
            raise ValueError("tool_assignment requires tool_name and server_code")
        if tool in seen_tools:
            raise ValueError(f"duplicate tool assignment: {tool}")
        seen_tools.add(tool)
        seen_servers.add(server)

    for row in servers:
        if not isinstance(row, dict):
            raise ValueError("servers items must be objects")
        code = str(row.get("server_code") or "").strip()
        if code:
            seen_servers.add(code)

    server_values: List[str] = []
    for row in servers:
        code = row.get("server_code")
        if not code:
            continue
        server_values.append(
            "("
            + ", ".join(
                [
                    sql_quote(code),
                    sql_quote(row.get("server_name") or code),
                    sql_quote(row.get("description")),
                    sql_quote(row.get("base_url")),
                    sql_quote(row.get("transport_type") or "HTTP"),
                    "TRUE" if bool(row.get("enabled", True)) else "FALSE",
                ]
            )
            + ")"
        )

    assign_values = [
        f"({sql_quote(row['tool_name'])}, {sql_quote(row['server_code'])})"
        for row in assignments
    ]

    if not assign_values:
        raise ValueError("tool_assignments cannot be empty")

    sql_lines: List[str] = []
    sql_lines.append("BEGIN;")
    sql_lines.append("")
    sql_lines.append("-- 1) Ensure target servers exist")
    if server_values:
        sql_lines.append(
            "INSERT INTO mcp_server_registry (server_code, server_name, description, base_url, transport_type, enabled)"
        )
        sql_lines.append("VALUES")
        sql_lines.append(",\n".join(server_values))
        sql_lines.append("ON CONFLICT (server_code) DO UPDATE SET")
        sql_lines.append("  server_name = EXCLUDED.server_name,")
        sql_lines.append("  description = EXCLUDED.description,")
        sql_lines.append("  base_url = EXCLUDED.base_url,")
        sql_lines.append("  transport_type = EXCLUDED.transport_type,")
        sql_lines.append("  enabled = EXCLUDED.enabled,")
        sql_lines.append("  updated_at = CURRENT_TIMESTAMP;")
    else:
        sql_lines.append("-- no server upserts in source")
    sql_lines.append("")
    sql_lines.append("-- 2) Build tool->server mapping temp table")
    sql_lines.append("CREATE TEMP TABLE tmp_tool_server_assign(tool_name varchar(200), server_code varchar(100));")
    sql_lines.append("INSERT INTO tmp_tool_server_assign(tool_name, server_code)")
    sql_lines.append("VALUES")
    sql_lines.append(",\n".join(assign_values) + ";")
    sql_lines.append("")
    sql_lines.append("-- 3) Reassign tool catalog")
    sql_lines.append("UPDATE mcp_tool_catalog c")
    sql_lines.append("SET server_code = m.server_code, updated_at = CURRENT_TIMESTAMP")
    sql_lines.append("FROM tmp_tool_server_assign m")
    sql_lines.append("WHERE c.tool_name = m.tool_name")
    sql_lines.append("  AND c.server_code <> m.server_code;")
    sql_lines.append("")
    sql_lines.append("-- 4) Reassign tool impl mapping")
    sql_lines.append("UPDATE mcp_tool_impl_mapping i")
    sql_lines.append("SET server_code = m.server_code, updated_at = CURRENT_TIMESTAMP")
    sql_lines.append("FROM tmp_tool_server_assign m")
    sql_lines.append("WHERE i.tool_name = m.tool_name")
    sql_lines.append("  AND i.server_code <> m.server_code;")
    sql_lines.append("")
    sql_lines.append("-- 5) Best-effort prompt reassignment by legacy bean mapping")
    sql_lines.append("WITH bean_server AS (")
    sql_lines.append("  SELECT DISTINCT i.bean_name, i.server_code")
    sql_lines.append("  FROM mcp_tool_impl_mapping i")
    sql_lines.append("  WHERE i.bean_name IS NOT NULL")
    sql_lines.append(")")
    sql_lines.append("UPDATE mcp_prompt_catalog p")
    sql_lines.append("SET server_code = b.server_code, updated_at = CURRENT_TIMESTAMP")
    sql_lines.append("FROM bean_server b")
    sql_lines.append("WHERE p.raw_payload IS NOT NULL")
    sql_lines.append("  AND p.raw_payload ? 'legacyBeanName'")
    sql_lines.append("  AND p.raw_payload->>'legacyBeanName' = b.bean_name")
    sql_lines.append("  AND p.server_code <> b.server_code;")
    sql_lines.append("")
    sql_lines.append("-- 6) Report coverage")
    sql_lines.append("SELECT")
    sql_lines.append("  (SELECT COUNT(*) FROM tmp_tool_server_assign) AS assigned_tools,")
    sql_lines.append("  (SELECT COUNT(*) FROM mcp_tool_catalog c JOIN tmp_tool_server_assign m ON c.tool_name = m.tool_name AND c.server_code = m.server_code) AS tool_catalog_matched,")
    sql_lines.append("  (SELECT COUNT(*) FROM mcp_tool_impl_mapping i JOIN tmp_tool_server_assign m ON i.tool_name = m.tool_name AND i.server_code = m.server_code) AS impl_mapping_matched;")
    sql_lines.append("")
    sql_lines.append("COMMIT;")
    sql_lines.append("")
    return "\n".join(sql_lines)


def list_tool_names(tool_dir: str) -> Set[str]:
    out: Set[str] = set()
    if not tool_dir or not os.path.isdir(tool_dir):
        return out
    for name in os.listdir(tool_dir):
        if name.lower().endswith(".json"):
            out.add(name[:-5])
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Reassign MCP tool data to multiple MCP servers.")
    parser.add_argument(
        "--source",
        default="scripts/migration_sources/tool_server_assignment.json",
        help="JSON source file with servers and tool_assignments",
    )
    parser.add_argument(
        "--output-sql",
        default="scripts/migration_sources/reassign_mcp_server_data.sql",
        help="Generated SQL output path",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply generated SQL using psql",
    )
    parser.add_argument(
        "--verify-tool-dir",
        default="json/tool",
        help="Verify assignments cover all tool json names; empty to skip",
    )
    parser.add_argument(
        "--db-url",
        default=os.environ.get("DATABASE_URL", ""),
        help="PostgreSQL URL for psql when --apply is set",
    )
    args = parser.parse_args()

    if not os.path.isfile(args.source):
        print(f"source file not found: {args.source}", file=sys.stderr)
        return 2

    try:
        source = load_json(args.source)
        if args.verify_tool_dir:
            expected = list_tool_names(args.verify_tool_dir)
            assigned = {
                str(it.get("tool_name", "")).strip()
                for it in (source.get("tool_assignments") or [])
                if isinstance(it, dict)
            }
            missing = sorted(expected - assigned)
            extra = sorted(assigned - expected)
            if missing or extra:
                if missing:
                    print("missing tool assignments: " + ", ".join(missing), file=sys.stderr)
                if extra:
                    print("extra tool assignments: " + ", ".join(extra), file=sys.stderr)
                return 2
        sql = build_sql(source)
    except Exception as e:
        print(f"build SQL failed: {e}", file=sys.stderr)
        return 2

    os.makedirs(os.path.dirname(args.output_sql), exist_ok=True)
    with open(args.output_sql, "w", encoding="utf-8") as f:
        f.write(sql)
    print(f"generated: {args.output_sql}")

    if not args.apply:
        return 0

    if not args.db_url:
        print("--apply requires --db-url or DATABASE_URL", file=sys.stderr)
        return 2

    cmd = ["psql", args.db_url, "-f", args.output_sql]
    proc = subprocess.run(cmd, check=False)
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
