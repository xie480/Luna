# Luna 项目

## ✨ 项目概述
Luna 是一款本地化的 AI 桌面助理，面向 **陪伴式人格 + 长期记忆 + 主动行为** 的全栈 AI Agent 平台。它集成了多模型驱动、RAG 知识库、MCP（模型上下文协议）工具调用、OpenClaw 任务编排以及 CodeOps 工程闭环，能够在本地桌面环境中实现 **自然语言对话、知识检索、主动规划、代码自动化** 等完整 AI 工作流。

> **核心价值**：在保证数据安全的前提下，通过统一的 **Plan/Phase/Node** 编排模型，实现从 **对话** 到 **任务执行** 的全链路可审计、可恢复、可编排。

---

## 📦 关键特性

- **深度陪伴 & 人格稳定**：基于 `PromptTemplates.SYSTEM_PROMPT`（[`src/main/java/org/yilena/luna/prompt/PromptTemplates.java:1`](src/main/java/org/yilena/luna/prompt/PromptTemplates.java:1)）实现人格约束，支持情绪表达（EmotionEnum）和 Live2D、TTS 多模态交互。
- **多层记忆体系**：短期上下文（Redis）、中期数据库、长期文件持久化，实现跨会话、跨设备的记忆保持。
- **自主感知 & 主动交互**：感知桌面状态、自动触发对话或建议，支持自我进化（向量知识库自动写入、提示词动态调节）。
- **多模型驱动**：支持 Ollama、本地 Qwen、Gemini 等模型（见 `client/OllamaClient.java`），提供灵活的模型切换能力。
- **MCP（模型上下文协议）**：通过统一的 Tool/Skill 接口，实现安全的文件读取、系统指令、桌面操作等能力（`json/tool/*.json` 系列定义）。
- **OpenClaw 编排引擎**：基于 **Plan → Phase → Node** 的 DAG 结构，支持全局规划、阶段调度、局部重规划、失败恢复及可视化（文档 `docs/openclaw_mvp_runbook.md`）。
- **CodeOps 工程闭环**：自动生成补丁、运行测试、质量门禁、自动修复并生成报告（`tools`、`json/tool/*.json`），实现 **读‑写‑测‑修‑报** 完整闭环。
- **企业级治理**：审计日志、定时备份、监控告警、审批流程（`ApprovalController.java`）等全套治理能力。

---

## 🛠️ 项目结构概览

```
.
├─ docs/                     # 设计、方案、指南文档
│   ├─ architecture_design_luna_v2.md   # 系统整体架构概述
│   ├─ api_documentation_mcp.md          # MCP 接口文档
│   ├─ execution-memory-ledger.md         # Execution Memory Ledger 设计
│   └─ …
├─ json/                     # Tool、Skill JSON Schema 定义
│   ├─ tool/…
│   └─ skill/…
├─ src/
│   ├─ main/java/org/yilena/luna/
│   │   ├─ RunaApplication.java            # SpringBoot 主入口
│   │   │   ↳ [`src/main/java/org/yilena/luna/RunaApplication.java:1`](src/main/java/org/yilena/luna/RunaApplication.java:1)
│   │   ├─ controller/                    # REST API 控制层
│   │   │   ├─ ChatController.java          # 对话入口
│   │   │   │   ↳ [`src/main/java/org/yilena/luna/controller/ChatController.java:1`](src/main/java/org/yilena/luna/controller/ChatController.java:1)
│   │   │   └─ …
│   │   ├─ service/                       # 业务服务层
│   │   ├─ tools/                        # MCP Tool 实现
│   │   └─ prompt/                       # Prompt 管理与治理
│   └─ resources/
│       ├─ application.yaml               # Spring 配置文件
│       │   ↳ [`src/main/resources/application.yaml:1`](src/main/resources/application.yaml:1)
│       └─ …
├─ scripts/                 # 项目迁移、运维脚本
├─ pom.xml                 # Maven 项目构建文件
└─ README.md               # 本文件
```

---

## 🚀 快速开始

### 1️⃣ 前置条件
- **JDK 17+**（推荐 AdoptOpenJDK）
- **Maven 3.8+**
- **Docker**（可选，用于本地 Ollama /模型容器）
- **Redis**（用于短期记忆）

### 2️⃣ 构建项目
```bash
# 克隆仓库（已在本地）
cd F:/YilenaCode/Luna
# 使用 Maven 编译并打包
mvn clean package -DskipTests
```
> 如需运行单元测试，请去掉 `-DskipTests`。

### 3️⃣ 启动服务
```bash
# 运行 SpringBoot 主程序
java -jar target/luna-*.jar
```
服务默认监听 **8080** 端口，可通过 `application.yaml` 中的 `server.port` 调整。

### 4️⃣ 调用示例
- **对话接口**：`POST http://localhost:8080/api/chat`
  - 请求体参见 `docs/frontend_api_reference.md`
- **工具调用**：使用 MCP `tool/*` JSON 定义，可通过 `Skill` 编排序列化调用。

> 完整 API 与使用示例请参考 [`docs/api_documentation_mcp.md`](docs/api_documentation_mcp.md:1)。

---

## 📚 文档中心
- **系统架构**：[`docs/architecture_design_luna_v2.md`](docs/architecture_design_luna_v2.md:1)
- **MCP 详细说明**：[`docs/mcp.md`](docs/mcp.md:1)
- **OpenClaw 任务编排**：[`docs/openclaw_mvp_runbook.md`](docs/openclaw_mvp_runbook.md:1)
- **Execution Memory Ledger**：[`docs/execution-memory-ledger.md`](docs/execution-memory-ledger.md:1)
- **技术细节与最佳实践**：[`docs/technical_highlights.md`](docs/technical_highlights.md:1)

---

## 🙋‍♀️ 联系我们
- **Issue**: 在 GitHub 提交 Issue 描述需求或 Bug。
- **邮件**: `yilena0505@163.com`

---
