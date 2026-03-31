#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SQL_ROOT = ROOT / "src" / "main" / "resources" / "sql"
GATE_FILE = ROOT / "docs" / "release" / "legacy-read-gate.json"

DROP_PATTERN = re.compile(r"drop\s+table(?:\s+if\s+exists)?\s+(?:public\.)?(mcp_tools|mcp_skills)\b", re.IGNORECASE)


def has_legacy_drop_statement() -> bool:
    if not SQL_ROOT.exists():
        return False
    for path in SQL_ROOT.rglob("*.sql"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        if DROP_PATTERN.search(text):
            print(f"[INFO] detected legacy drop statement in {path.relative_to(ROOT).as_posix()}")
            return True
    return False


def gate_passed() -> bool:
    if not GATE_FILE.exists():
        print(f"[FAIL] gate file not found: {GATE_FILE.as_posix()}")
        return False
    try:
        payload = json.loads(GATE_FILE.read_text(encoding="utf-8"))
    except Exception as ex:
        print(f"[FAIL] invalid gate file json: {ex}")
        return False

    versions = payload.get("versions")
    if not isinstance(versions, list) or len(versions) < 2:
        print("[FAIL] gate requires at least 2 versions")
        return False

    last_two = versions[-2:]
    for row in last_two:
        tools = row.get("mcp_tools_read_count")
        skills = row.get("mcp_skills_read_count")
        if tools != 0 or skills != 0:
            print("[FAIL] legacy read gate not satisfied for last two versions")
            print(f"[FAIL] row={row}")
            return False
    return True


def main() -> int:
    if not has_legacy_drop_statement():
        print("[PASS] no legacy table drop statement detected")
        return 0

    if gate_passed():
        print("[PASS] legacy drop gate satisfied")
        return 0

    print("[FAIL] block drop table mcp_tools/mcp_skills until reads are zero for 2 versions")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

