# Legacy MCP Retirement Checklist

## Release Gate

1. `legacy_mcp_write_audit` has no successful write bypass, and all attempted writes are blocked.
2. `legacy_mcp_read_metric` shows `mcp_tools` and `mcp_skills` read counts are both `0` for the latest 2 released versions.
3. `docs/release/legacy-read-gate.json` is updated with the latest 2 release rows.
4. If SQL change contains `DROP TABLE mcp_tools` or `DROP TABLE mcp_skills`, CI gate `check_legacy_drop_gate.py` must pass.
5. Compatibility docs and rollback SQL are kept for at least one release after physical drop.

## CI Commands

```bash
python scripts/ci/check_legacy_reference_guard.py
python scripts/ci/check_legacy_drop_gate.py
```

## DB Query

Use [query_legacy_read_gate.sql](/F:/YilenaCode/Luna/scripts/release/query_legacy_read_gate.sql) to populate `legacy-read-gate.json`.

