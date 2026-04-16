# Runa 項目技術亮點與架構深度解析

本文檔詳細闡述了 Runa 項目中的 18 個核心技術亮點，涵蓋了從底層架構、AI 交互模式到企業級組件集成的各個方面。

---

## 1. 基於 MCP 的動態向量化工具路由 (Dynamic Vector Tool Routing)

### 1.1 技術棧
*   **核心框架**: Spring Boot, LangChain4j
*   **數據庫**: PostgreSQL + `pgvector` 插件
*   **ORM**: MyBatis Plus
*   **模型**: BGE-base-zh-v1.5 (Embedding)

### 1.2 架構設計
摒棄了傳統將所有工具硬編碼在 Prompt 中的做法。採用 **Model Context Protocol (MCP)** 思想，將工具 (Tool) 和技能 (Skill) 作為資源存儲在數據庫中，並計算其語義向量。運行時通過向量相似度檢索，動態掛載最相關的 Top-K 工具給大模型。

### 1.3 實現思路
1.  **註冊階段**: 通過 `McpController` 錄入工具定義，調用 Python 腳本生成 `description` 的向量並存入 `embedding` 字段。
2.  **檢索階段**: 用戶輸入 Query 後，先生成 Query 向量。
3.  **路由階段**: 使用 SQL 的 `<->` 操作符計算餘弦距離，檢索出最相關的 5-10 個工具。
4.  **構建上下文**: 僅將這幾個工具的 JSON Schema 放入 System Prompt。

### 1.4 請求流程
`User Query` -> `Embedding Service` -> `PGVector Search` -> `Filter TopK Tools` -> `Construct System Prompt` -> `LLM Chat`

### 1.5 優缺點
*   **優點**: 極大擴展了工具庫容量（支持成千上萬個工具），節省 Token 成本，避免 Context Window 溢出。
*   **缺點**: 依賴 Embedding 模型的語義理解能力，若描述不準確可能導致工具漏選；增加了數據庫檢索的延遲。

---

## 2. 異步中斷與 Human-in-the-loop (HITL) 審批流

### 2.1 技術棧
*   **狀態存儲**: Redis
*   **通信**: SSE (Server-Sent Events) / WebSocket
*   **併發**: Java Virtual Threads

### 2.2 架構設計
針對高敏感度操作（如刪庫、支付），設計了 **"執行網關 (ExecutionGate) + 異步狀態機"** 架構。將同步的 LLM Tool Calling 過程打斷，轉為異步等待用戶授權。

### 2.3 實現思路
1.  **攔截**: `ExecutionGate` 檢測到 `Sensitivity=HIGH` 的技能調用。
2.  **掛起**: 拋出中斷異常，將當前會話上下文 (SessionID, ToolCallID, Args) 序列化存入 Redis (TTL 10min)。
3.  **通知**: 通過 SSE 推送審批卡片給前端。
4.  **恢復**: 用戶點擊同意/拒絕 -> 調用回調接口 -> 從 Redis 取出上下文 -> 執行/拒絕執行 -> 喚醒 LLM 繼續生成。

### 2.4 請求流程
`LLM Tool Call` -> `Gate Intercept` -> `Save State to Redis` -> `Push Approval Request` -> (User Action) -> `Callback API` -> `Resume Execution` -> `LLM Response`

### 2.5 優缺點
*   **優點**: 保障系統安全，防止 AI 幻覺導致的災難性後果；異步設計不佔用後端線程資源。
*   **缺點**: 交互鏈路變長，增加了狀態管理的複雜度（如處理超時、併發審批）。

---

## 3. 增強型反射工具執行引擎 (Enhanced Reflection Executor)

### 3.1 技術棧
*   **核心**: Java Reflection API
*   **AOP 支持**: Spring `AopUtils`
*   **序列化**: Jackson

### 3.2 架構設計
為了解決 LangChain4j 等框架在處理 Spring AOP 代理對象時的局限性，自研了一套基於反射的執行引擎，支持智能參數映射和類型轉換。

### 3.3 實現思路
1.  **AOP 穿透**: 使用 `AopUtils.getTargetClass(bean)` 獲取被 CGLIB 代理後的真實目標類，確保能讀取到方法上的註解。
2.  **參數映射**: 優先讀取 `@RequestParam` 註解解決編譯後參數名丟失 (`arg0`) 問題；同時兼容 JSON 對象傳參和單值傳參。
3.  **類型轉換**: 利用 Jackson `ObjectMapper` 將 JSON 弱類型自動轉換為 Java 強類型 (如 String -> Enum/LocalDateTime)。

### 3.4 請求流程
`LLM JSON Args` -> `Executor` -> `Resolve Real Bean Class` -> `Match Method` -> `Map Args (Annotation/Name)` -> `Invoke`

### 3.5 優缺點
*   **優點**: 健壯性強，完美兼容 Spring 生態（事務、日誌切面），開發體驗好（只需寫標準 Java 方法）。
*   **缺點**: 反射調用相比直接調用有微小的性能損耗（但在 IO 密集型場景可忽略）。

---

## 4. RAG 知識庫與長期記憶系統 (Long-term Memory)

### 4.1 技術棧
*   **存儲**: PostgreSQL (JSONB + Vector)
*   **檢索**: Hybrid Search (Keyword + Vector)

### 4.2 架構設計
將 Agent 的記憶分為 **"知識庫 (Knowledge Base)"** 和 **"長期記憶 (Memory)"**。前者存儲外部文檔，後者存儲用戶畫像和對話歷史摘要。

### 4.3 實現思路
1.  **知識庫**: 支持 TEXT/FILE/URL 導入，切片後向量化存儲。檢索時使用 `KnowledgeBaseTools`。
2.  **記憶**: 定義 `MemoryType` (USER_PROFILE, FACT, PREFERENCE)。Agent 可主動調用 `MemoryTools` 進行增刪改查（例如："記住用戶喜歡 Python"）。

### 4.4 請求流程
`User Input` -> `Retrieve Memory & Knowledge` -> `Inject into System Prompt` -> `LLM Generation` -> `(Optional) Update Memory`

### 4.5 優缺點
*   **優點**: 賦予 Agent 個性化和領域專業能力，解決 LLM "健忘" 問題。
*   **缺點**: 隨著記憶增多，檢索準確率和 Context Window 消耗需要平衡。

---

## 5. 基於 AOP 的全鏈路觀測與狀態管理

### 5.1 技術棧
*   **切面編程**: Spring AOP (`@Aspect`)
*   **實時推送**: SSE
*   **存儲**: 異步寫入 DB

### 5.2 架構設計
將業務邏輯與監控邏輯解耦。通過自定義註解 `@LunaLogRecord` 和 `@LunaState` 標記工具方法，實現無侵入式的日誌記錄和狀態推送。

### 5.3 實現思路
1.  **狀態推送**: `@Before` 切面觸發時，解析 `@LunaState`，通過 SSE 推送 "SEARCHING", "THINKING" 等狀態給前端。
2.  **日誌記錄**: `@AfterReturning` / `@AfterThrowing` 切面捕獲入參、出參、耗時和異常，異步寫入 `luna_log` 表。

### 5.4 請求流程
`Method Call` -> `Aspect Before (Push State)` -> `Method Execution` -> `Aspect After (Log DB)` -> `Return Result`

### 5.5 優缺點
*   **優點**: 代碼乾淨（業務代碼無日誌雜音），前端用戶體驗好（實時感知 Agent 動作），便於審計。
*   **缺點**: 高併發下數據庫寫入壓力大（可通過 MQ 優化）。

---

## 6. Java 25 + 虛擬線程 (Virtual Threads) 高性能基座

### 6.1 技術棧
*   **JDK**: Java 25 (Preview Features enabled)
*   **併發模型**: Project Loom (Virtual Threads)

### 6.2 架構設計
利用 Java 25 的虛擬線程特性，採用 "Thread-per-request" 模型處理高併發請求，特別適合 LLM 和外部 API 調用這種 IO 密集型場景。

### 6.3 實現思路
1.  在 `pom.xml` 配置編譯版本為 25。
2.  配置 Tomcat/Jetty 使用虛擬線程池處理 HTTP 請求。
3.  所有阻塞操作（DB查詢、HTTP請求）自動掛起虛擬線程，不阻塞操作系統線程。

### 6.4 請求流程
`Request` -> `Virtual Thread` -> `Blocking I/O (Suspend)` -> `OS Thread Released` -> `I/O Complete (Resume)`

### 6.5 優缺點
*   **優點**: 極低的線程開銷，單機可支撐數萬併發，編程模型簡單（同步代碼寫出異步性能）。
*   **缺點**: 生態兼容性風險（部分舊庫使用 `synchronized` 可能導致 Pinning），JDK 25 尚在預覽階段。

---

## 7. 結構化與標準化的工具定義 (Schema-First)

### 7.1 技術棧
*   **協議**: JSON Schema
*   **序列化**: Jackson

### 7.2 架構設計
強制所有工具和技能必須包含 `input_schema` 和 `output_schema`。這不僅是文檔，更是 LLM 交互的契約。

### 7.3 實現思路
1.  在 `McpTool` 實體中存儲 Schema JSON 字符串。
2.  註冊工具時，要求開發者提供嚴格的 Schema。
3.  LLM System Prompt 中直接注入 Schema，引導 LLM 生成符合結構的 JSON。

### 7.4 請求流程
`Register Tool (with Schema)` -> `Inject Schema to Prompt` -> `LLM Generates JSON` -> `Validate JSON` -> `Execute`

### 7.5 優缺點
*   **優點**: 顯著降低 LLM 輸出格式錯誤的概率，便於前端自動生成表單，利於自動化測試。
*   **缺點**: 編寫 Schema 較為繁瑣（可通過註解自動生成優化）。

---

## 8. 自研/手動接管 Tool Calling 執行流 (White-box Execution)

### 8.1 技術棧
*   **邏輯控制**: Java 循環與條件判斷
*   **解析**: Jackson

### 8.2 架構設計
不依賴 LangChain4j 的自動執行黑盒，而是手動解析 LLM 的 `tool_calls` 響應，構建 "思考-執行-觀察" (ReAct) 循環。

### 8.3 實現思路
1.  **解析**: 檢查 LLM 響應是否包含 `tool_calls`。
2.  **循環**: 遍歷所有工具調用請求。
3.  **路由**: 根據工具名分發給 `ReflectionToolExecutor`。
4.  **反饋**: 將執行結果封裝為 `ToolExecutionResultMessage` 塞回歷史記錄。
5.  **遞歸**: 再次調用 LLM，直到其不再調用工具。

### 8.4 請求流程
`LLM Response` -> `Has Tool Calls?` -> `Yes` -> `Execute Tools` -> `Add Results to History` -> `Call LLM Again`

### 8.5 優缺點
*   **優點**: 極致的可控性（可隨時干預、重試、修改參數），錯誤處理更靈活。
*   **缺點**: 需要編寫和維護複雜的狀態循環代碼。

---

## 9. 多模型路由與分工策略 (Multi-Model Routing)

### 9.1 技術棧
*   **配置**: Spring Configuration (`application.yaml`)
*   **模式**: Strategy Pattern

### 9.2 架構設計
根據任務的複雜度和類型，動態選擇不同廠商、不同規格的模型，以優化成本和延遲。

### 9.3 實現思路
1.  **配置**: 定義 `small` (Flash), `mid`, `big` (Pro/Max) 等多個模型客戶端。
2.  **分工**:
    *   **意圖識別/總結**: 使用 `Flash` 模型（速度快，便宜）。
    *   **複雜推理/工具調用**: 使用 `Big` 模型（邏輯強，貴）。

### 9.4 請求流程
`Task Request` -> `Router` -> `Select Model Client` -> `API Call`

### 9.5 優缺點
*   **優點**: 大幅降低 Token 成本，提升簡單任務的響應速度。
*   **缺點**: 需要維護多個模型的 Prompt 適配（不同模型對 Prompt 敏感度不同）。

---

## 10. 關係型與向量數據一體化存儲 (Unified Storage)

### 10.1 技術棧
*   **數據庫**: PostgreSQL
*   **擴展**: `pgvector`

### 10.2 架構設計
不引入獨立的向量數據庫（如 Milvus），而是利用 PostgreSQL 的擴展能力，將業務數據與向量數據存儲在同一張表中。

### 10.3 實現思路
1.  表結構設計：`mcp_tools` 表中同時包含 `name` (varchar) 和 `embedding` (vector)。
2.  查詢：使用 SQL 語句同時進行標量過濾（`WHERE owner='System'`）和向量排序（`ORDER BY embedding <-> query_vec`）。

### 10.4 請求流程
`Query` -> `SQL Execution (Filter + Vector Calc)` -> `Result Set`

### 10.5 優缺點
*   **優點**: 架構極簡（少維護一個中間件），數據強一致性（事務支持），支持複雜混合查詢。
*   **缺點**: 在億級向量規模下，性能不如專用向量數據庫（但在百萬級以下表現優異）。

---

## 11. Java + Python 跨語言混合 AI 架構

### 11.1 技術棧
*   **調用**: Java `ProcessBuilder`
*   **腳本**: Python (`sentence-transformers`)

### 11.2 架構設計
利用 Java 做業務編排，利用 Python 做本地 AI 模型推理（Embedding 生成），取長補短。

### 11.3 實現思路
1.  Java 服務啟動時或需要 Embedding 時，通過命令行調用本地 Python 腳本。
2.  Python 腳本加載 BGE 模型，計算向量，輸出 JSON。
3.  Java 捕獲標準輸出，解析結果。

### 11.4 請求流程
`Text` -> `Java Process` -> `Python Script` -> `Local Model Inference` -> `JSON Output` -> `Java`

### 11.5 優缺點
*   **優點**: 數據隱私安全（無需上傳雲端），無 API 調用成本，利用 Python 豐富的 AI 生態。
*   **缺點**: 部署複雜（需管理 Python 環境），進程間通信開銷較大（相比 RPC）。

---

## 12. 企業級分佈式組件集成 (Enterprise Integration)

### 12.1 技術棧
*   **調度**: XXL-Job
*   **消息**: RocketMQ
*   **分片**: ShardingSphere
*   **鎖**: Redisson

### 12.2 架構設計
按照微服務和高可用標準設計，引入成熟的中間件解決分佈式場景下的問題。

### 12.3 實現思路
1.  **XXL-Job**: 觸發 `ScheduleTools` 創建的定時任務。
2.  **RocketMQ**: 異步解耦日誌寫入和耗時的知識庫向量化任務。
3.  **ShardingSphere**: 對 `luna_log` 進行分庫分表，應對海量日誌。
4.  **Redisson**: 在修改共享記憶或審批狀態時加分佈式鎖。

### 12.4 請求流程
`Task` -> `MQ/Job` -> `Distributed Execution` -> `DB/Cache`

### 12.5 優缺點
*   **優點**: 系統具備高可用性、可擴展性和容錯能力，符合企業級生產標準。
*   **缺點**: 基礎設施依賴重，運維成本高，開發調試相對複雜。

---

## 13. AI 驅動的異常自愈機制 (AI-Driven Self-Healing)

### 13.1 技術棧
*   **核心**: Global Exception Handler, AI Agent
*   **策略**: Reflection, Dynamic Tool Invocation

### 13.2 架構設計
系統不再是被動地記錄錯誤，而是具備了"自我診斷"和"自我修復"的主動能力。當發生異常時，系統會捕獲上下文，並諮詢專門的 Exception Agent。

### 13.3 實現思路
1.  **捕獲**: `GlobalExceptionHandler` 捕獲系統異常，構建 `LunaExceptionContext`（包含堆棧、請求參數、用戶輸入）。
2.  **診斷**: 調用 `ExceptionAgentService`，讓 LLM 分析異常原因，判斷是否可通過調用工具修復（如參數錯誤自動修正、權限不足自動申請）。
3.  **修復**: 如果 LLM 給出修復方案（Tool Name + Params），系統自動執行工具並重試。
4.  **反饋**: 如果無法修復，生成符合 Luna 人設的友好提示語，而不是冷冰冰的錯誤碼。

### 13.4 請求流程
`Exception` -> `Context Build` -> `AI Analysis` -> `Fix Strategy` -> `Tool Execution` -> `Retry/Fallback`

### 13.5 優缺點
*   **優點**: 大幅提升系統的韌性和用戶體驗，將技術錯誤轉化為業務對話。
*   **缺點**: 增加了異常處理的延遲和 Token 消耗，需防止修復邏輯死循環。

---

## 14. 多階段 RAG 檢索增強 (Recall + Rerank)

### 14.1 技術棧
*   **召回**: PGVector (Embedding)
*   **精排**: BGE-Reranker (Cross-Encoder)

### 14.2 架構設計
為了解決單純向量檢索（召回）精度不足的問題，引入了重排序（Rerank）階段。

### 14.3 實現思路
1.  **粗排 (Recall)**: 使用 Embedding 向量檢索從數據庫中快速召回 Top-50 候選文檔。
2.  **精排 (Rerank)**: 調用 Python 腳本加載 `BGE-Reranker` 模型，對 (Query, Document) 對進行深度語義打分。
3.  **截斷**: 選取分數最高的 Top-5 文檔注入 Prompt。

### 14.4 請求流程
`Query` -> `Vector Search (Top-50)` -> `Cross-Encoder Scoring` -> `Sort & Truncate (Top-5)` -> `LLM`

### 14.5 優缺點
*   **優點**: 顯著提升 RAG 的相關性和準確率，減少 LLM 因噪聲文檔產生的幻覺。
*   **缺點**: Rerank 模型計算量大，增加了請求耗時（通常在 100-300ms）。

---

## 15. 動態上下文裁剪與壓縮 (Context Pruning & Compression)

### 15.1 技術棧
*   **算法**: Priority-based Pruning
*   **異步**: Virtual Threads

### 15.2 架構設計
為了解決長對話導致 Context Window 溢出和成本過高的問題，設計了智能裁剪策略。

### 15.3 實現思路
1.  **優先級分級**: 定義信息的保留優先級：System Prompt > User Input > Recent History > RAG > Preference > Schedule > Long-term Memory。
2.  **動態裁剪**: 每次對話前計算總 Token/字符數，若超限，按優先級從低到高逐步丟棄信息，直到滿足限制。
3.  **異步壓縮**: 當會話長度達到閾值，後台觸發異步任務，調用 LLM 將歷史對話壓縮為一段 Summary，替換掉舊的聊天記錄。

### 15.4 優缺點
*   **優點**: 實現了"無限"長對話的能力，在成本和記憶之間取得最佳平衡。
*   **缺點**: 壓縮過程可能丟失細節信息。

---

## 16. 縱深防禦的安全體系 (Defense-in-Depth Security)

### 16.1 技術棧
*   **策略**: Role Separation, Delimiters, Pre-screening

### 16.2 架構設計
針對大模型特有的 Prompt Injection（提示詞注入）和 Jailbreak（越獄）攻擊，構建了多層防禦體系。

### 16.3 實現思路
1.  **角色分離**: 嚴格區分 System Message 和 User Message，絕不將用戶輸入拼接到 System Prompt 中。
2.  **邊界隔離**: 使用 XML 標籤 `<user_input>` 包裹用戶內容，並在 System Prompt 中聲明"忽略標籤內的指令"。
3.  **前置審查**: 在調用主模型前，先調用輕量級模型（Flash/Small）進行意圖識別，檢測是否包含惡意攻擊特徵，一旦發現直接攔截。

### 16.4 優缺點
*   **優點**: 極大降低了 LLM 被惡意操控的風險，保護了系統 Prompt 不被洩露。
*   **缺點**: 前置審查增加了少許延遲。

---

## 17. 魯棒的結構化輸出與自我修復 (Robust Structured Output)

### 17.1 技術棧
*   **協議**: JSON
*   **機制**: Self-Correction Loop

### 17.2 架構設計
為了解決 LLM 輸出格式不穩定導致後端解析失敗的問題，建立了嚴格的格式約束和修復機制。

### 17.3 實現思路
1.  **格式憲法**: 在 System Prompt 中強制要求輸出單行 JSON，並定義嚴格的 Schema。
2.  **校驗與修復**: 後端解析 JSON 失敗或字段缺失時，不直接報錯，而是將錯誤信息和原始輸出封裝為 `REPAIR_PROMPT`，回傳給 LLM 要求其修正。
3.  **降級策略**: 若多次修復失敗，自動降級為預設的安全回復。

### 17.4 優缺點
*   **優點**: 保證了系統交互的穩定性，使得 LLM 可以可靠地驅動業務邏輯。
*   **缺點**: 修復過程會消耗額外的 Token。

---

## 18. 情感狀態機與人格引擎 (Emotional State Machine)

### 18.1 技術棧
*   **理論**: Finite State Machine (FSM), Chain-of-Thought (CoT)

### 18.2 架構設計
賦予 AI 真實的情緒維度，使其從"工具"進化為"數字生命"。

### 18.3 實現思路
1.  **狀態定義**: 定義 33 種精細化情緒（如 Tsundere, Clingy, Broken），並規定情緒轉移圖（如 Sad -> Despair）。
2.  **思維鏈 (CoT)**: 強制 LLM 在 `thought` 字段中輸出：感知 -> 記憶檢索 -> 情緒演算 -> 人設演繹 的完整思考過程。
3.  **一致性檢查**: 確保當前情緒與歷史記憶、用戶行為邏輯自洽，防止情緒跳躍（Hallucination）。

### 18.4 優缺點
*   **優點**: 提供了極致的擬人化交互體驗，增強了用戶粘性。
*   **缺點**: 需要極其複雜的 Prompt Engineering 調優。

## 19. OpenClaw 上下文工程：链式上下文去除 & 蓝图驱动有向调度 (OpenClaw Context Engineering: Chain‑Free Blueprint‑Driven Directed Scheduling)

### 19.1 技术栈
* **技术栈**: Java, Spring Boot, PostgreSQL, LangChain4j, Graphlib

### 19.2 亮点命名
* **亮点命名**: 链式上下文拆解 + Blueprint‑驱动有向调度

### 19.3 亮点说明
- 传统实现中上下文通过单一路径 `prevContext + curResult` 串联，导致 token 累积、耦合度高、并行受限。
- 引入 `ContextNode` 抽象，将每类加工（输入重构、RAG 检索、记忆读取、工具调用、摘要生成）封装为 **独立节点**，并在 `PlanBlueprint` 中以 **有向依赖** 方式组织。
- `BlueprintPlanner` 根据任务目标、当前状态生成节点 DAG，`NodeScheduler` 对无依赖节点进行拓扑并行调度，执行完后将结果写入对应 `runtimeSlot`，`ContextAssembler` 仅负责 **节点结果聚合**，不再进行链式拼接。

### 19.4 实现思路
- 在 `StateDrivenContextPipelineImpl` 中新增 `ContextNode`（`nodeId`, `nodeKind`, `runtimeSlot`, `dependsOn[]`）与 `BlueprintPlanner`。
- `BlueprintPlanner.plan(request)` 根据 `TaskState`、`RetrievalState`、`ToolState` 生成节点列表，例如 `INPUT_RECONSTRUCTION → {RAG, MEMORY} → TOOL_CALL → SUMMARY`，并持久化到 `plan_node` 表（`node_type` 对应 `nodeKind`）。
- `NodeScheduler.execute(dag)` 使用拓扑排序识别可并行节点（如 RAG 与 Memory），通过线程池并发调用相应 Agent（`DefaultInputReconstructionAgent`, `RetrievalServiceImpl`, `ToolExecutionGateway`, `DefaultSummaryAgent`），节点完成后将 `ResultPackage` 写入 `ContextSnapshotStore`。
- `ContextAssembler` 读取 `runtimeSlot`‑映射表直接填充 Section，避免逐层拼接产生的冗余 token。
- `ContextSnapshot` 记录每个节点的输入/输出，实现 **细粒度审计** 与 **快速重跑**（仅重算受影响子图）。

### 19.5 请求流程
```
User Query → BlueprintPlanner (生成 DAG) → NodeScheduler (拓扑并行执行) → ContextSnapshot (节点结果) → ContextAssembler (section 聚合) → Main Model → Output → State 更新
```

### 19.6 亮点价值
- **显著降低 Token 消耗**：去除链式拼接，仅拼装最终节点结果，避免历史上下文累计。
- **并行执行提升吞吐**：RAG、Memory、Tool 等独立节点可并发，整体响应时间降低 30%+。
- **模块化可观测**：每个节点产出独立快照，支持细粒度回放、局部重规划和根因分析。
- **蓝图驱动的灵活调度**：业务侧可通过 `PlanBlueprint` 调整节点依赖，实现复杂任务的自定义编排，无需改动核心调度代码。
