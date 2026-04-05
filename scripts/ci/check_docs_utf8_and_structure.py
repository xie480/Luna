#!/usr/bin/env python3
from pathlib import Path
import sys


DOC_RULES = {
    "docs/context.md": [
        "# Luna 基于 Small Agent 的上下文工程架构设计（正式版）",
        "## 6. Input Reconstruction Agent（输入重构 Agent）",
        "## 7. RAG 集成方案",
        "## 8. MCP 集成方案",
        "## 12. Context Assembler（上下文组装器）",
        "## 16. 可观测性与审计设计",
    ],
    "docs/mcp.md": [
        "# 一、先给结论：你当前系统应如何定义迁移目标",
        "# 三、迁移时的总体架构目标",
        "# 阶段 2：新增 MCP 核心目录表，不直接删旧表",
        "# 阶段 4：代码架构迁移方案",
    ],
    "docs/memory.md": [
        "# 0. 先给最终结论",
        "# 4. 双状态机设计",
        "# 6. 数据库设计",
        "# 12. Memory Write Pipeline",
    ],
    "docs/rag.md": [
        "# 通用 RAG 模块设计文档",
        "## 8. 四类链路设计",
        "## 9. Router 设计",
        "## 11. Retriever 设计",
    ],
}


def main() -> int:
    failures: list[str] = []
    for rel_path, required_headings in DOC_RULES.items():
        path = Path(rel_path)
        if not path.exists():
            failures.append(f"[missing] {rel_path} not found")
            continue
        raw = path.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf"):
            failures.append(f"[encoding] {rel_path} must be UTF-8 without BOM")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            failures.append(f"[encoding] {rel_path} is not valid UTF-8: {exc}")
            continue
        if "\ufffd" in text:
            failures.append(f"[encoding] {rel_path} contains replacement char U+FFFD")
        for heading in required_headings:
            if heading not in text:
                failures.append(f"[structure] {rel_path} missing heading: {heading}")
    if failures:
        print("Documentation UTF-8/structure check failed:")
        for item in failures:
            print(f" - {item}")
        return 1
    print("Documentation UTF-8/structure check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

