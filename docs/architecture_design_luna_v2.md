# Luna 2.0：企业级 MCP 平台架构设计方案

## 1. 项目背景与目标

当前 `Luna` 项目是一个基于 Spring Boot 的单体应用，具备基础的 LLM 对话、RAG（文本切片、Embedding）、SSE 流式输出以及多模型配置（Ollama, Qwen）功能。

**本次升级目标**：
根据 `need.txt` 的需求，将项目重构为**多模块 Maven 工程**，引入 **MCP (Model Context Protocol)** 标准，实现「厂商级 Tool Calling + Skill 编排 + 权限管控」平台。

## 2. 项目结构重构 (Maven Multi-Module)

我们将现有的 `src/main/java/org/yilena/luna/*` 代码拆分并迁移至以下模块结构：

