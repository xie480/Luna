from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(r"F:/YilenaCode/Luna")
SRC_ROOT = ROOT / "src/main/java"

TYPE_DECL_RE = re.compile(r"^\s*(public\s+)?(class|interface|enum)\s+([A-Za-z0-9_]+)")
FIELD_RE = re.compile(
    r"^\s*(private|protected|public)\s+(static\s+final\s+|final\s+|static\s+)?[A-Za-z0-9_<>, ?\[\].@]+\s+([A-Za-z0-9_]+)\s*(=.*)?;$"
)
METHOD_RE = re.compile(
    r"^\s*(public|protected|private)\s+(static\s+)?[A-Za-z0-9_<>, ?\[\].@]+\s+([A-Za-z0-9_]+)\s*\([^;]*\)\s*(throws [^{]+)?\{$"
)
ENUM_CONST_RE = re.compile(r"^\s*([A-Z0-9_]+)\s*(\(|,|;)")
OPERATION_RE = re.compile(r"^(\s*)@Operation\(.*\)\s*$")
TAG_RE = re.compile(r"^(\s*)@Tag\(.*\)\s*$")


WORD_MAP = {
    "chat": "对话",
    "approval": "审批",
    "auth": "认证",
    "login": "登录",
    "logout": "登出",
    "status": "状态",
    "stream": "流",
    "mcp": "MCP",
    "rpc": "协议调用",
    "query": "查询",
    "knowledge": "知识",
    "base": "库",
    "memory": "记忆",
    "user": "用户",
    "preference": "偏好",
    "rag": "RAG",
    "prompt": "Prompt",
    "plan": "计划",
    "phase": "阶段",
    "report": "报告",
    "graph": "图",
    "legacy": "兼容",
    "page": "分页",
    "history": "历史",
    "date": "日期",
    "message": "消息",
    "session": "会话",
    "runtime": "运行时",
    "tool": "工具",
    "resource": "资源",
    "server": "服务端",
    "registry": "注册",
    "catalog": "目录",
    "workflow": "工作流",
    "task": "任务",
    "node": "节点",
    "event": "事件",
    "log": "日志",
    "summary": "摘要",
    "context": "上下文",
    "state": "状态",
    "snapshot": "快照",
    "config": "配置",
    "property": "配置项",
    "response": "响应",
    "request": "请求",
    "result": "结果",
    "detail": "详情",
    "list": "列表",
    "item": "条目",
    "category": "分类",
    "version": "版本",
    "preview": "预览",
    "policy": "策略",
    "admin": "管理",
    "controller": "控制器",
    "service": "服务",
    "impl": "实现",
}


FIELD_PHRASE_MAP = {
    "id": "唯一标识",
    "code": "编码",
    "name": "名称",
    "title": "标题",
    "type": "类型",
    "status": "状态",
    "level": "级别",
    "content": "内容",
    "message": "消息内容",
    "description": "描述信息",
    "summary": "摘要内容",
    "reason": "原因说明",
    "path": "路径",
    "url": "地址",
    "uri": "资源标识",
    "json": "JSON 内容",
    "text": "文本内容",
    "count": "数量",
    "size": "大小",
    "page": "页码",
    "token": "令牌",
    "query": "查询条件",
    "input": "输入内容",
    "output": "输出内容",
    "request": "请求参数",
    "response": "响应结果",
    "result": "处理结果",
    "time": "时间",
    "at": "时间点",
    "date": "日期",
    "enabled": "是否启用",
    "deleted": "是否删除",
    "priority": "优先级",
    "score": "评分",
    "version": "版本号",
    "operator": "操作人",
    "creator": "创建人",
    "updater": "更新人",
}


BUSINESS_SKIP_SEGMENTS = {
    "entity",
    "enums",
    "constants",
    "config",
    "mapper",
    "properties",
    "llm",
    "state/model",
    "state/store",
    "model",
    "dto",
}


def split_words(name: str) -> list[str]:
    parts = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name).replace("_", " ").split()
    return [p.lower() for p in parts]


def phrase_from_words(words: list[str]) -> str:
    translated = [WORD_MAP.get(word, word.upper() if word.isupper() else word) for word in words]
    text = "".join(
        item if re.search(r"[\u4e00-\u9fffA-Z]", item) else item
        for item in translated
    )
    return text or "业务"


def contains_chinese(text: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", text))


def previous_comment_range(lines: list[str], idx: int) -> tuple[int, int] | None:
    i = idx - 1
    while i >= 0 and lines[i].strip() == "":
        i -= 1
    if i < 0:
        return None
    if lines[i].lstrip().startswith("//"):
        start = i
        while start - 1 >= 0 and lines[start - 1].lstrip().startswith("//"):
            start -= 1
        return start, i + 1
    if lines[i].rstrip().endswith("*/"):
        end = i + 1
        start = i
        while start >= 0 and "/*" not in lines[start]:
            start -= 1
        if start >= 0:
            return start, end
    return None


def replace_comment_block(lines: list[str], idx: int, comment_lines: list[str]) -> list[str]:
    rng = previous_comment_range(lines, idx)
    if rng:
        start, end = rng
        existing = "".join(lines[start:end])
        if contains_chinese(existing) and "??" not in existing:
            return lines
        del lines[start:end]
        idx = start
    lines[idx:idx] = comment_lines
    return lines


def leading_annotation_start(lines: list[str], idx: int) -> int:
    start = idx
    while start - 1 >= 0 and lines[start - 1].lstrip().startswith("@"):
        start -= 1
    return start


def class_comment(path: Path, type_name: str, kind: str) -> str:
    text = phrase_from_words(split_words(type_name.replace(kind.capitalize(), "")))
    path_str = str(path).replace("\\", "/")
    if "/controller/" in path_str or path_str.endswith("/controller"):
        return f"/**\n * {text}控制器，负责接收{text}相关请求并协调服务层完成接口处理。\n */\n"
    if "/service/impl/" in path_str:
        return f"/**\n * {text}服务实现类，负责落地{text}相关业务编排、状态流转与结果处理。\n */\n"
    if "/service/" in path_str:
        return f"/**\n * {text}服务接口，定义{text}领域的核心能力边界。\n */\n"
    if "/entity/" in path_str:
        return f"/**\n * {text}实体类，用于承载{text}领域的数据结构与持久化字段。\n */\n"
    if "/enums/" in path_str:
        return f"/**\n * {text}枚举类，用于约束{text}相关状态或类型取值。\n */\n"
    if "/constants/" in path_str:
        return f"/**\n * {text}常量类，集中定义{text}场景复用的固定值。\n */\n"
    if "/config/" in path_str:
        return f"/**\n * {text}配置类，负责注册{text}相关的 Spring Bean 与运行参数。\n */\n"
    if "/mapper/" in path_str:
        return f"/**\n * {text}数据访问接口，负责执行{text}相关的数据读写映射。\n */\n"
    if "/prompt/governance/api/" in path_str:
        return f"/**\n * {text}控制器，负责 Prompt 治理相关的查询、变更与版本管理接口。\n */\n"
    return f"/**\n * {text}{'枚举' if kind == 'enum' else '类'}，负责承载当前模块的核心职责与协作能力。\n */\n"


def field_comment_text(name: str, path: Path) -> str:
    words = split_words(name)
    joined = "".join(words)
    if name.endswith("Id"):
        return f"用于标识{phrase_from_words(words[:-1] or words)}的唯一标识。"
    if name.endswith("Ids"):
        return f"用于批量关联{phrase_from_words(words[:-1] or words)}的标识集合。"
    if name.endswith("Code"):
        return f"表示{phrase_from_words(words[:-1] or words)}的业务编码。"
    if name.endswith("Name"):
        return f"表示{phrase_from_words(words[:-1] or words)}的展示名称。"
    if name.endswith("Status"):
        return f"表示{phrase_from_words(words[:-1] or words)}的当前状态。"
    if name.endswith("Type"):
        return f"表示{phrase_from_words(words[:-1] or words)}的分类类型。"
    if name.endswith("Time") or name.endswith("At"):
        return f"记录{phrase_from_words(words[:-1] or words)}的时间信息。"
    if name.startswith("is") and len(words) > 1:
        return f"标识{phrase_from_words(words[1:])}是否成立。"
    for word in reversed(words):
        if word in FIELD_PHRASE_MAP:
            return f"表示{phrase_from_words([w for w in words if w != word]) or '当前对象'}的{FIELD_PHRASE_MAP[word]}。"
    if "/constants/" in str(path).replace("\\", "/"):
        return f"定义{phrase_from_words(words)}相关的固定常量值。"
    return f"表示{phrase_from_words(words)}相关的业务属性。"


def enum_comment_text(name: str) -> str:
    return f"表示{phrase_from_words(split_words(name))}这一业务取值。"


def subject_from_path(path: Path, method_name: str) -> str:
    base = path.stem.replace("Controller", "").replace("ServiceImpl", "").replace("Service", "")
    if method_name in {"page", "list", "search"}:
        return phrase_from_words(split_words(base or "query"))
    return phrase_from_words(split_words(method_name))


def method_comment(path: Path, method_name: str) -> str:
    path_str = str(path).replace("\\", "/")
    subject = subject_from_path(path, method_name)
    if "/controller/" in path_str or "/prompt/governance/api/" in path_str:
        return f"/**\n * 处理{subject}相关接口请求，负责完成参数接收、必要校验与响应返回。\n */\n"
    if "/service/impl/" in path_str:
        return f"/**\n * 执行{subject}业务流程，按既定编排完成上下文准备、核心处理与结果收敛。\n */\n"
    return f"/**\n * 处理{subject}相关逻辑，保证当前模块协作链路稳定可读。\n */\n"


def operation_text(path: Path, method_name: str) -> tuple[str, str]:
    words = split_words(method_name)
    base = phrase_from_words(split_words(path.stem.replace("Controller", "")))
    if method_name == "chat":
        return "发送对话消息", "接收用户输入并驱动完整的对话编排、工具决策与回复生成流程。"
    if method_name == "startup":
        return "执行启动会话", "触发系统启动阶段的欢迎响应、上下文恢复与状态初始化流程。"
    if method_name == "shutdown":
        return "执行结束会话", "在会话关闭前完成状态收敛、必要落盘与连接清理。"
    if method_name.startswith("get") or method_name.startswith("detail"):
        return f"获取{base}详情", f"查询{base}相关明细数据，并返回当前请求对应的结果。"
    if method_name.startswith("list") or method_name == "categories" or method_name == "items" or method_name == "versions":
        return f"查询{base}列表", f"按当前条件返回{base}相关列表数据，便于前端展示与后续筛选。"
    if method_name == "page":
        return f"分页查询{base}", f"根据分页与过滤条件查询{base}数据，并统一封装分页结果。"
    if method_name.startswith("search"):
        return f"搜索{base}", f"根据请求条件检索{base}相关数据，并返回匹配结果。"
    if method_name.startswith("create") or method_name.startswith("register"):
        return f"新增{base}", f"接收新增请求并完成{base}数据的创建处理。"
    if method_name.startswith("update") or method_name.startswith("save"):
        return f"保存{base}", f"根据请求内容新增或更新{base}数据，保持配置与业务状态一致。"
    if method_name.startswith("delete") or method_name.startswith("remove"):
        return f"删除{base}", f"按请求标识删除或停用指定的{base}数据。"
    if method_name.startswith("sync"):
        return f"同步{base}", f"触发{base}相关目录或配置的同步流程，刷新系统可用数据。"
    if method_name.startswith("stream"):
        return "订阅状态流", "建立 SSE 长连接，持续推送运行状态变化给前端。"
    if method_name.startswith("disconnect"):
        return "断开状态流", "主动关闭当前状态推送连接，释放对应的会话资源。"
    if method_name.startswith("retrieve"):
        return "执行检索流程", "按治理规则改写检索请求并返回 RAG 检索结果。"
    if method_name.startswith("submit"):
        return "提交处理结果", "接收前端提交的处理结果并继续后续业务流转。"
    if method_name.startswith("call"):
        return f"调用{base}能力", f"根据请求参数执行{base}相关调用，并返回执行结果。"
    return f"处理{phrase_from_words(words)}", f"执行{base}相关接口逻辑，并返回当前请求对应的处理结果。"


def tag_text(path: Path) -> tuple[str, str]:
    stem = path.stem.replace("Controller", "")
    base = phrase_from_words(split_words(stem))
    if "/prompt/governance/api/" in str(path).replace("\\", "/"):
        return "Prompt 治理接口", "提供 Prompt 分类、条目、策略、版本与预览的管理能力。"
    return f"{base}接口", f"提供{base}相关的接口访问能力。"


def should_skip_business_comments(path: Path) -> bool:
    normalized = str(path).replace("\\", "/")
    return any(seg in normalized for seg in BUSINESS_SKIP_SEGMENTS)


def stage_comment(block: list[str]) -> str:
    text = " ".join(line.strip() for line in block[:6])
    lowered = text.lower()
    if "if (" in lowered and ("badrequest" in lowered or "unprocessableentity" in lowered or "return " in lowered):
        return "        // 先校验关键入参与前置条件，避免无效请求继续进入后续业务链路。\n"
    if "statuspublisher" in lowered or "sessionid" in lowered or "authcontextholder" in lowered:
        return "        // 初始化会话标识与运行状态，为后续编排、检索和响应生成提供统一上下文。\n"
    if "state-drivencontextpipeline" in lowered or "statedrivencontextpipeline" in lowered or "orchestrate" in lowered:
        return "        // 编排当前轮次所需的上下文、节点工作集与决策结果，为主流程执行提供输入基础。\n"
    if "wrapper." in lowered or "lambdaquerywrapper" in lowered:
        return "        // 按非空筛选条件组装查询约束，确保分页结果与前端过滤条件保持一致。\n"
    if "mapper." in lowered or "service." in lowered or ".save" in lowered or ".update" in lowered or ".insert" in lowered:
        return "        // 执行当前阶段的数据读写或服务调用，推动业务状态继续向后流转。\n"
    if "memorywritepipeline" in lowered or "persist" in lowered or "store" in lowered:
        return "        // 将本轮关键结果写入存储或审计链路，保证后续恢复、追踪与分析可用。\n"
    if "responseentity" in lowered or "return " in lowered:
        return "        // 汇总本阶段输出并构造返回结果，向上游明确反馈当前处理结论。\n"
    return "        // 处理当前业务阶段的核心逻辑，保证后续步骤能够基于这一阶段结果继续执行。\n"


def apply_type_comment(lines: list[str], path: Path) -> list[str]:
    for idx, line in enumerate(lines):
        match = TYPE_DECL_RE.match(line)
        if not match:
            continue
        start = leading_annotation_start(lines, idx)
        comment = class_comment(path, match.group(3), match.group(2))
        return replace_comment_block(lines, start, [comment])
    return lines


def apply_member_comments(lines: list[str], path: Path) -> list[str]:
    out: list[str] = []
    brace_depth = 0
    in_enum = False
    for i, line in enumerate(lines):
        stripped = line.strip()
        before_depth = brace_depth
        type_match = TYPE_DECL_RE.match(line)
        if type_match and type_match.group(2) == "enum":
            in_enum = True

        if before_depth == 1 and stripped and not stripped.startswith("@"):
            field_match = FIELD_RE.match(line)
            method_match = METHOD_RE.match(line)
            enum_match = ENUM_CONST_RE.match(line) if in_enum else None

            if field_match and "(" not in line and " class " not in line:
                rng = previous_comment_range(out, len(out))
                if not rng or not contains_chinese("".join(out[rng[0]:rng[1]])):
                    out.append(f"    /**\n     * {field_comment_text(field_match.group(3), path)}\n     */\n")
            elif method_match:
                rng = previous_comment_range(out, len(out))
                if not rng or not contains_chinese("".join(out[rng[0]:rng[1]])):
                    out.append(method_comment(path, method_match.group(3)))
            elif enum_match and not stripped.startswith("private "):
                rng = previous_comment_range(out, len(out))
                if not rng or not contains_chinese("".join(out[rng[0]:rng[1]])):
                    out.append(f"    /**\n     * {enum_comment_text(enum_match.group(1))}\n     */\n")

        out.append(line)
        brace_depth += line.count("{") - line.count("}")
        if in_enum and brace_depth == 0:
            in_enum = False
    return out


def apply_operation_and_tag(lines: list[str], path: Path) -> list[str]:
    for idx, line in enumerate(lines):
        op = OPERATION_RE.match(line)
        if op:
            method_name = ""
            for j in range(idx + 1, min(idx + 8, len(lines))):
                mm = METHOD_RE.match(lines[j].strip())
                if mm:
                    method_name = mm.group(3)
                    break
            summary, description = operation_text(path, method_name or "handle")
            lines[idx] = f'{op.group(1)}@Operation(summary = "{summary}", description = "{description}")\n'
        tag = TAG_RE.match(line)
        if tag:
            name, desc = tag_text(path)
            lines[idx] = f'{tag.group(1)}@Tag(name = "{name}", description = "{desc}")\n'
    return lines


def apply_stage_comments(lines: list[str], path: Path) -> list[str]:
    if should_skip_business_comments(path):
        return lines

    out: list[str] = []
    brace_depth = 0
    in_method = False
    method_base_depth = 0
    paragraph: list[str] = []
    inserted_for_paragraph = False

    def flush_pending():
        nonlocal paragraph, inserted_for_paragraph
        paragraph = []
        inserted_for_paragraph = False

    for line in lines:
        current_depth = brace_depth
        method_match = METHOD_RE.match(line.strip())
        if method_match and current_depth == 1:
            in_method = True
            method_base_depth = current_depth + line.count("{") - line.count("}")
            flush_pending()

        if in_method and brace_depth == method_base_depth and line.strip():
            if not inserted_for_paragraph and not line.lstrip().startswith("//") and not line.lstrip().startswith("/*") and not line.lstrip().startswith("@"):
                out.append(stage_comment(paragraph + [line]))
                inserted_for_paragraph = True
            if not line.lstrip().startswith("//") and not line.lstrip().startswith("/*"):
                paragraph.append(line)
        elif in_method and line.strip() == "":
            flush_pending()

        out.append(line)
        brace_depth += line.count("{") - line.count("}")
        if in_method and brace_depth < method_base_depth:
            in_method = False
            flush_pending()
    return out


def process_file(path: Path) -> None:
    original = path.read_text(encoding="utf-8")
    lines = original.splitlines(keepends=True)
    lines = apply_type_comment(lines, path)
    lines = apply_member_comments(lines, path)
    lines = apply_operation_and_tag(lines, path)
    lines = apply_stage_comments(lines, path)
    updated = "".join(lines)
    if updated != original:
        path.write_text(updated, encoding="utf-8")


def main() -> None:
    for path in SRC_ROOT.rglob("*.java"):
        normalized = str(path).replace("\\", "/")
        if "/target/" in normalized or "/src/test/" in normalized:
            continue
        process_file(path)


if __name__ == "__main__":
    main()
