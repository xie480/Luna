#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WHITELIST_FILE = ROOT / ".ci" / "legacy-reference-whitelist.txt"

SCAN_DIRS = (
    ROOT / "src" / "main" / "java",
    ROOT / "src" / "main" / "resources" / "sql",
)

PATTERNS = (
    re.compile(r"\bmcp_tools\b"),
    re.compile(r"\bmcp_skills\b"),
    re.compile(r"\bMcpToolMapper\b"),
    re.compile(r"\bMcpSkillMapper\b"),
)


def load_whitelist() -> set[str]:
    if not WHITELIST_FILE.exists():
        print(f"[ERROR] whitelist not found: {WHITELIST_FILE}")
        sys.exit(1)
    lines = [line.strip() for line in WHITELIST_FILE.read_text(encoding="utf-8").splitlines()]
    return {line for line in lines if line and not line.startswith("#")}


def scan_file(path: Path) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    text = path.read_text(encoding="utf-8", errors="ignore")
    for idx, line in enumerate(text.splitlines(), start=1):
        if any(p.search(line) for p in PATTERNS):
            hits.append((idx, line.strip()))
    return hits


def main() -> int:
    whitelist = load_whitelist()
    violations: list[str] = []

    for base in SCAN_DIRS:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file():
                continue
            rel = path.relative_to(ROOT).as_posix()
            if rel in whitelist:
                continue
            hits = scan_file(path)
            for line_no, snippet in hits:
                violations.append(f"{rel}:{line_no}:{snippet}")

    if violations:
        print("[FAIL] legacy reference guard: found non-whitelisted legacy references")
        for item in violations:
            print(item)
        print(f"[HINT] add exceptions only when necessary: {WHITELIST_FILE.as_posix()}")
        return 1

    print("[PASS] legacy reference guard")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
