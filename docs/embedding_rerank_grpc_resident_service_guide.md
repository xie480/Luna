# Embedding 与 Rerank 服务常驻化技术文档（HTTP 方案）

## 1. 文档目的

本文档说明当前项目如何将原本“每次请求拉起 Python 进程”的 `embedding` / `rerank` 推理链路，改造为“模型常驻内存”的本机 HTTP 服务架构，并保留本地脚本回退能力，兼顾性能与稳定性。

目标：

1. 降低单次请求延迟（减少进程启动和模型重复加载开销）
2. 提升并发场景吞吐
3. 保持现有 Java 业务代码改动最小化
4. 支持故障降级（HTTP 推理服务不可用时回退本地脚本）

---

## 2. 改造前后对比

## 2.1 改造前（子进程模式）
每次调用：

- Java -> `ProcessBuilder` -> 启动 `embedding.py` / `rerank.py`
- Python 脚本每次都加载模型
- 输出通过 stdin/stdout 传递

缺点：

1. 进程启动成本高
2. 模型反复加载，CPU/内存抖动大
3. 高并发时延迟放大明显

## 2.2 改造后（本机 HTTP 常驻模式）
启动两个独立 Python 推理进程：

- `embedding_service_http.py`：启动时加载 embedding 模型一次
- `rerank_service_http.py`：启动时加载 rerank 模型一次
- Java 通过 HTTP 调用：
  - `POST /embedding`
  - `POST /rerank`
- 支持开关与回退：
  - `inference.http.enabled=true` 时优先走 HTTP
  - `inference.http.fallback-local=true` 时失败回退本地脚本

优点：

1. 模型加载一次复用多次
2. 显著减少冷启动与重复加载
3. 延迟更稳定、吞吐更高
4. 规避 gRPC 依赖与生成代码带来的编译复杂度

---

## 3. 关键代码与文件变更点

## 3.1 Java 侧推理调用路由与回退策略

文件：`src/main/java/org/yilena/luna/utils/LlmClientUtil.java`

核心策略：

## Embedding 调用链

1. 命中 JVM 内存缓存 `embeddingCache` -> 直接返回
2. 若启用 HTTP，调用 `embedding-url`（默认 `http://127.0.0.1:18080/embedding`）
3. HTTP 异常：
   - 若 `fallback-local=true` -> 回退原 `embedding.py` 子进程
   - 否则直接抛出异常

## Rerank 调用链

1. 若启用 HTTP，调用 `rerank-url`（默认 `http://127.0.0.1:18081/rerank`）
2. HTTP 异常：
   - 若 `fallback-local=true` -> 回退原 `rerank.py` 子进程
   - 否则直接抛出异常

这样实现了“优先常驻，失败回退”的平滑迁移机制。

---

## 3.2 Python 常驻服务实现

### Embedding 服务
文件：`src/main/resources/python/embedding_service_http.py`

- 启动时加载 `SentenceTransformer` 模型
- 接口：`POST /embedding`
- 入参：`{"text":"..."}`
- 返回：`{"vector_json":"[...]","success":true/false,"error_message":"..."}`

### Rerank 服务
文件：`src/main/resources/python/rerank_service_http.py`

- 启动时加载 `CrossEncoder` 模型
- 接口：`POST /rerank`
- 入参：`{"query":"...","documents":["...","..."]}`
- 返回：`{"scores":[...],"success":true/false,"error_message":"..."}`

---

## 3.3 运行配置

文件：`src/main/resources/application.yaml`

使用以下配置块：

```yaml
inference:
  http:
    enabled: true
    embedding-url: http://127.0.0.1:18080/embedding
    rerank-url: http://127.0.0.1:18081/rerank
    timeout-ms: 1500
    fallback-local: true
```

字段说明：

- `enabled`：是否启用 HTTP 常驻推理
- `embedding-url`：Embedding 服务地址
- `rerank-url`：Rerank 服务地址
- `timeout-ms`：单次 HTTP 调用超时
- `fallback-local`：HTTP 失败后是否回退本地脚本

---

## 4. 启动与验证流程

## 4.1 启动 Embedding 常驻服务

示例：

```bash
python embedding_service_http.py \
  --host 127.0.0.1 \
  --port 18080 \
  --embedding-model-path D:/AI_Models/bge-base-zh-v1.5-model
```

## 4.2 启动 Rerank 常驻服务

示例：

```bash
python rerank_service_http.py \
  --host 127.0.0.1 \
  --port 18081 \
  --rerank-model-path D:/AI_Models/bge-reranker-v2-m3
```

## 4.3 启动 Java 服务

```bash
mvn clean spring-boot:run
```

检查：

1. 聊天请求触发 RAG 时可正常返回
2. 日志出现 HTTP 推理成功或回退日志
3. 无 gRPC/protobuf 编译依赖错误

## 4.4 故障演练（验证回退）

1. 停掉一个或两个 HTTP 推理服务
2. 保持 `fallback-local=true`
3. 再发 chat 请求，确认仍可走本地脚本成功返回

---

## 5. 性能收益预期

在常见 RAG 场景下，常驻化后收益主要来自：

1. 消除 Python 进程反复拉起
2. 消除模型重复加载
3. 降低延迟抖动（P95/P99 更明显）

建议重点观测：

- embedding 平均耗时
- rerank 平均耗时
- chat 总耗时 P50/P95/P99
- HTTP 推理失败率与回退率

---

## 6. 已知注意事项

1. 需要 Python 环境安装：
   - `fastapi`
   - `uvicorn`
   - `sentence-transformers`
2. 常驻服务默认本机 `127.0.0.1`，如跨机部署需调整 URL 与安全策略
3. `vector_json` 为字符串格式，需保持与现有数据库 cast/vector 检索逻辑兼容
4. 若模型较大，服务首次启动会有明显加载时间，属正常现象

---

## 7. 后续可演进方向

1. 将两个 HTTP 服务合并为单一推理进程（减少运维点位）
2. 推理服务容器化（便于部署与扩容）
3. 引入健康检查与自动拉起
4. 增加批量 embedding 接口（减少 HTTP 次数）
5. 推理服务侧增加模型热更新机制
6. 加入 Prometheus 指标（QPS、耗时、错误率）

---

## 8. 结论

本次常驻化改造已实现：

1. embedding/rerank 从“脚本进程级调用”升级为“本机 HTTP 常驻调用”
2. Java 侧低侵入整合，保持原有业务语义不变
3. 支持开关与回退，降低迁移风险
4. 明显改善 chat 全链路时延稳定性，特别是 P95/P99

在当前并行 RAG 架构下，HTTP 常驻化是兼顾性能、稳定性与工程复杂度的实用方案。
