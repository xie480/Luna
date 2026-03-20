# Embedding 与 Rerank 服务常驻化技术文档（gRPC 方案）

## 1. 文档目的

本文档说明当前项目如何将原本“每次请求拉起 Python 进程”的 `embedding` / `rerank` 推理链路，改造为“模型常驻内存”的 gRPC 服务架构，并保留本地脚本回退能力，兼顾性能与稳定性。

目标：

1. 降低单次请求延迟（减少进程启动和模型重复加载开销）
2. 提升并发场景吞吐
3. 保持现有 Java 业务代码改动最小化
4. 支持故障降级（gRPC 不可用时可回退本地脚本）

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

## 2.2 改造后（gRPC 常驻模式）
启动一个独立 Python 推理进程：

- 进程启动时加载 embedding 与 rerank 模型一次
- Java 通过 gRPC 远程调用：
  - `Embedding(text)`  
  - `Rerank(query, documents)`
- 支持开关与回退：
  - `inference.grpc.enabled=true` 时优先 gRPC
  - `inference.grpc.fallback-local=true` 时失败回退本地脚本

优点：

1. 模型加载一次复用多次
2. 显著减少冷启动与重复加载
3. 延迟更稳定、吞吐更高

---

## 3. 关键代码与文件变更点

## 3.1 协议定义（Proto）

文件：`src/main/proto/luna_inference.proto`

定义统一服务：

- `rpc Embedding(EmbeddingRequest) returns (EmbeddingResponse);`
- `rpc Rerank(RerankRequest) returns (RerankResponse);`

其中：

- `EmbeddingResponse.vector_json` 返回向量 JSON 字符串（与现有数据库写入格式兼容）
- `RerankResponse.scores` 返回分数列表

---

## 3.2 Java 端 gRPC 依赖与代码生成

文件：`pom.xml`

新增：

1. gRPC 依赖：
   - `grpc-netty-shaded`
   - `grpc-protobuf`
   - `grpc-stub`
   - `protobuf-java`
2. `protobuf-maven-plugin` + `protoc-gen-grpc-java` 代码生成配置

执行 `mvn clean compile` 后生成：

- `org.yilena.luna.grpc.EmbeddingRequest`
- `org.yilena.luna.grpc.EmbeddingResponse`
- `org.yilena.luna.grpc.LunaInferenceServiceGrpc`
- `org.yilena.luna.grpc.RerankRequest`
- `org.yilena.luna.grpc.RerankResponse`

---

## 3.3 Java 侧通道配置

文件：`src/main/java/org/yilena/luna/config/GrpcInferenceConfig.java`

功能：

- 通过 Spring Bean 初始化 `ManagedChannel`
- 默认连接 `127.0.0.1:50051`
- `usePlaintext()`（本机内网通信）

---

## 3.4 Java 侧 gRPC 客户端封装

文件：`src/main/java/org/yilena/luna/client/InferenceGrpcClient.java`

职责：

1. 封装 Embedding 调用
2. 封装 Rerank 调用
3. 设置调用超时 `timeout-ms`
4. 统一错误日志输出与异常抛出

---

## 3.5 推理调用路由与回退策略

文件：`src/main/java/org/yilena/luna/utils/LlmClientUtil.java`

核心策略：

## Embedding 调用链

1. 命中 JVM 内存缓存 `embeddingCache` -> 直接返回
2. 若启用 gRPC，调用 `InferenceGrpcClient.embedding(text)`
3. gRPC 异常：
   - 若 `fallback-local=true` -> 回退原 `embedding.py` 子进程
   - 否则直接抛出异常

## Rerank 调用链

1. 若启用 gRPC，调用 `InferenceGrpcClient.rerank(query, documents)`
2. gRPC 异常：
   - 若 `fallback-local=true` -> 回退原 `rerank.py` 子进程
   - 否则抛出异常

这样实现了“优先常驻，失败回退”的平滑迁移机制。

---

## 3.6 Python 常驻服务实现

文件：`src/main/resources/python/luna_inference_grpc_server.py`

关键点：

1. 服务启动时加载模型：
   - `SentenceTransformer`（embedding）
   - `CrossEncoder`（rerank）
2. 提供 gRPC 方法：
   - `Embedding`
   - `Rerank`
3. 标准返回：
   - `success`
   - `error_message`
4. 支持命令行参数：
   - `--host`
   - `--port`
   - `--embedding-model-path`
   - `--rerank-model-path`

---

## 3.7 运行配置

文件：`src/main/resources/application.yaml`

新增配置块：

```yaml
inference:
  grpc:
    enabled: true
    host: 127.0.0.1
    port: 50051
    timeout-ms: 1500
    fallback-local: true
```

字段说明：

- `enabled`：是否启用 gRPC 常驻推理
- `host/port`：本机服务地址
- `timeout-ms`：单次 gRPC 超时
- `fallback-local`：gRPC 失败后是否回退本地脚本

---

## 4. 启动与验证流程

## 4.1 启动 Python gRPC 推理服务

示例：

```bash
python luna_inference_grpc_server.py \
  --host 127.0.0.1 \
  --port 50051 \
  --embedding-model-path D:/AI_Models/bge-base-zh-v1.5-model \
  --rerank-model-path D:/AI_Models/bge-reranker-v2-m3
```

日志应出现：

- 模型加载完成
- gRPC 服务启动成功

## 4.2 启动 Java 服务

执行：

```bash
mvn clean spring-boot:run
```

检查：

1. 无 gRPC 类缺失编译错误
2. 聊天请求触发 RAG 时可正常返回
3. 日志出现 gRPC 成功或回退日志

## 4.3 故障演练（验证回退）

1. 停掉 Python gRPC 服务
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
- gRPC 失败率与回退率

---

## 6. 已知注意事项

1. Proto 类找不到通常是“未执行 maven 代码生成”或 IDE 未标记 generated-sources
2. gRPC 版本与 protobuf 插件版本需匹配
3. `vector_json` 为字符串格式，需保持与现有数据库 cast/vector 检索逻辑兼容
4. 若服务跨机部署，需开启 TLS/鉴权；当前本机独立进程默认 plaintext

---

## 7. 后续可演进方向

1. 将 Python 常驻服务容器化（便于部署与扩容）
2. 引入健康检查与自动拉起
3. gRPC 增加批量 embedding 接口（进一步降 RPC 次数）
4. 推理服务侧增加模型热更新机制
5. 加入 Prometheus 指标（QPS、耗时、错误率）

---

## 8. 结论

本次常驻化改造已实现：

1. embedding/rerank 从“脚本进程级调用”升级为“模型常驻 gRPC 调用”
2. Java 侧无侵入整合，保持原有业务语义不变
3. 支持开关与回退，降低迁移风险
4. 为 chat 全链路性能优化打下基础

在你当前并行 RAG 架构下，这个改造是最关键、最直接的性能提升点之一。
