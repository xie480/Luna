#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
One-click MCP migration bootstrap.

Behavior:
1) Auto-generate missing/empty skill source files.
2) Run API migration from ._mcp_migration.sql.
3) Backfill catalog entries from legacy rows when needed.
4) Trigger json catalog sync.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from typing import List


def run(cmd: List[str]) -> int:
    print("[run] " + " ".join(cmd))
    proc = subprocess.run(cmd, check=False)
    return proc.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description="Bootstrap MCP migration with sensible defaults.")
    parser.add_argument("--base-url", default="http://localhost:8080", help="Service base URL")
    parser.add_argument("--token", default="", help="Bearer token")
    parser.add_argument("--sql-file", default="._mcp_migration.sql", help="Migration SQL source")
    parser.add_argument("--timeout", type=int, default=20, help="HTTP timeout seconds")
    parser.add_argument("--sleep-ms", type=int, default=0, help="Sleep between requests (ms)")
    parser.add_argument("--dry-run", action="store_true", help="Parse only")
    args = parser.parse_args()

    script = os.path.join("scripts", "migrate_mcp_via_api.py")
    cmd = [
        sys.executable,
        script,
        "--sql-file",
        args.sql_file,
        "--base-url",
        args.base_url,
        "--timeout",
        str(args.timeout),
        "--sleep-ms",
        str(args.sleep_ms),
        "--backfill-tools",
        "--backfill-skills",
        "--sync-json",
        "--auto-bootstrap",
    ]
    if args.token:
        cmd.extend(["--token", args.token])
    if args.dry_run:
        cmd.append("--dry-run")
    return run(cmd)


if __name__ == "__main__":
    raise SystemExit(main())
