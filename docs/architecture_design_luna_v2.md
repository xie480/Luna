# Luna v2.0 企業級 MCP 平台架構設計文檔 (去框架化純數據驅動版)

## 1. 項目背景與升級目標

### 1.1 當前架構痛點 (v1.x)
當前 Luna 項目是一個基於 Spring Boot 的單體應用，工具調用（Tool Calling）強依賴於 LangChain4j 框架的 `@Tool` 註解。這種模式存在以下局限性：
1. **工具與代碼強耦合**：新增或修改工具描述、參數結構必須修改代碼並重新發布。
2. **缺乏統一管控**：無法對高危工具進行細粒度的權限攔截（Execution Gate）和人工審批（Human-in-the-loop）。
3. **擴展性差**：難以支持耗時較長的異步複合技能（Skill）。

### 1.2 v2.0 升級目標
根據企業級需求，v2.0 將重構為**多模組 Maven 工程**，完全自主實現 **MCP (Model Context Protocol)** 標準。                                                                                                 
核心設計哲學：**「工具定義與執行解耦，一切皆數據」**。
- 廢棄 `@Tool` 註解，工具元數據（Schema、描述、路由）全部存入數據庫。
- 實現動態反射執行引擎（Reflection Executor）。
- 引入標準化的註冊、發現、校驗與審批機制。

---                                                                                                                                                                                                      

## 2. 系統總體架構 (Maven Multi-Module)

項目將拆分為 7 個職責單一的核心模組：

```text                                                                                                                                                                                                  
luna-root (父工程)                                                                                                                                                                                       
├── luna-common             # [公共模組] 核心實體(Resource), 異常體系, JSON校驗工具                                                                                                                      
├── luna-mcp-server         # [註冊中心] 負責 Tool/Skill 的元數據管理 (CRUD + 語義檢索)                                                                                                                  
├── luna-llm-adapter        # [模型適配] 統一 LLM 接口，隔離具體廠商 (OpenAI/Gemini/Mock)                                                                                                                
├── luna-tool-executor      # [工具執行] 核心反射引擎，負責解析 JSON 並動態調用 Spring Bean                                                                                                              
├── luna-skill-executor     # [技能執行] 負責複合技能編排 (審批流/異步任務)                                                                                                                              
├── luna-agent-orchestrator # [編排核心] 系統大腦，負責意圖分析、決策、參數生成與流程驅動                                                                                                                
└── luna-control-plane      # [控制平面] (可選) 用於管理 MCP 資源的後台 UI 接口                                                                                                                          
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


3. 核心模組詳細設計                                                                                                                                                                                      

3.1 公共模組 (luna-common)                                                                                                                                                                               

定義系統的「真理來源 (Source of Truth)」和通用契約。                                                                                                                                                     

核心實體 Resource (MCP 資源定義):                                                                                                                                                                        

                                                                                                                                                                                                         
package org.yilena.luna.common.entity;                                                                                                                                                                   
                                                                                                                                                                                                         
import lombok.Data;                                                                                                                                                                                      
                                                                                                                                                                                                         
@Data                                                                                                                                                                                                    
public class Resource {                                                                                                                                                                                  
    private String id;            // 唯一標識 (UUID)                                                                                                                                                     
    private String type;          // 類型: "TOOL" (原子工具) 或 "SKILL" (複合技能)                                                                                                                       
    private String name;          // 工具名稱 (如 "web_search")，LLM 決策時輸出此名稱                                                                                                                    
    private String description;   // 用於 LLM 理解工具用途的詳細描述                                                                                                                                     
    private String version;       // 版本號 (如 "1.0.0")                                                                                                                                                 
    private String owner;         // 負責人/所屬模組                                                                                                                                                     
                                                                                                                                                                                                         
    // 執行映射 (替代原有的 @Tool 註解)                                                                                                                                                                  
    private String beanName;      // Spring Bean 的名稱 (如 "searchTools")                                                                                                                               
    private String methodName;    // 方法名稱 (如 "web_search")                                                                                                                                          
                                                                                                                                                                                                         
    // 契約定義                                                                                                                                                                                          
    private String inputSchema;   // JSON Schema 字符串 (嚴格定義參數結構)                                                                                                                               
    private String outputSchema;  // 預期輸出結構的 JSON Schema                                                                                                                                          
                                                                                                                                                                                                         
    // 運行時控制                                                                                                                                                                                        
    private String runMode;       // 運行模式: "SYNC" (同步) 或 "ASYNC" (異步)                                                                                                                           
    private Boolean requiresApproval; // 是否需要人工審批 (true/false)                                                                                                                                   
    private String sensitivity;   // 權限/敏感等級: "LOW", "MEDIUM", "HIGH"                                                                                                                              
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

JSON Schema 校驗器 JsonSchemaValidator: 用於在 LLM 生成參數後，執行前進行強制校驗，防止幻覺參數導致系統崩潰。                                                                                            

                                                                                                                                                                                                         
package org.yilena.luna.common.utils;                                                                                                                                                                    
                                                                                                                                                                                                         
public class JsonSchemaValidator {                                                                                                                                                                       
    /**                                                                                                                                                                                                  
     * 校驗 JSON 字符串是否符合指定的 JSON Schema                                                                                                                                                        
     * @param schema JSON Schema 字符串                                                                                                                                                                  
     * @param json LLM 生成的參數 JSON 字符串                                                                                                                                                            
     * @return 是否校驗通過                                                                                                                                                                              
     */                                                                                                                                                                                                  
    public static boolean validate(String schema, String json) {                                                                                                                                         
        // 具體實現可依賴 networknt/json-schema-validator 或自定義 Jackson 邏輯                                                                                                                          
        return true;                                                                                                                                                                                     
    }                                                                                                                                                                                                    
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

3.2 MCP 註冊中心 (luna-mcp-server)                                                                                                                                                                       

負責管理 resources 表，提供工具的註冊與發現能力。                                                                                                                                                        

核心接口:                                                                                                                                                                                                

 • POST /mcp/resources: 註冊新的工具或技能。                                                                                                                                                             
 • GET /mcp/resources/{id}: 獲取資源詳情。                                                                                                                                                               
 • POST /mcp/search: (核心) 根據用戶 Query 進行檢索。                                                                                                                                                    
    • 實現邏輯：接收用戶輸入，通過向量數據庫（PGVector）或關鍵詞匹配，從 resources 表中檢索出最相關的 Top N 個工具，返回給 Orchestrator 構建 Prompt。                                                    

3.3 模型適配層 (luna-llm-adapter)                                                                                                                                                                        

隔離底層 LLM SDK，提供統一的生成接口。                                                                                                                                                                   

接口定義:                                                                                                                                                                                                

                                                                                                                                                                                                         
public interface LlmAdapter {                                                                                                                                                                            
    String generate(String prompt);                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

Mock 實現 (用於本地無 Key 測試與調試):                                                                                                                                                                   

                                                                                                                                                                                                         
@Service                                                                                                                                                                                                 
public class MockLlmAdapter implements LlmAdapter {                                                                                                                                                      
    @Override                                                                                                                                                                                            
    public String generate(String prompt) {                                                                                                                                                              
        if (prompt.contains("决策")) {                                                                                                                                                                   
            // 模擬 LLM 決定調用 web_search 工具                                                                                                                                                         
            return "{\"tool_name\": \"web_search\"}";                                                                                                                                                    
        }                                                                                                                                                                                                
        if (prompt.contains("args")) {                                                                                                                                                                   
            // 模擬 LLM 生成的參數                                                                                                                                                                       
            return "{\"query\": \"今天的天气\"}";                                                                                                                                                        
        }                                                                                                                                                                                                
        return "{\"emotion\":\"Smile\",\"reply\":\"这是 Mock 模型的默认回复。\"}";                                                                                                                       
    }                                                                                                                                                                                                    
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

3.4 執行引擎層 (Executors & Gate)                                                                                                                                                                        

3.4.1 安全網關 (Execution Gate)                                                                                                                                                                          

所有工具/技能在執行前必須經過此網關。                                                                                                                                                                    

                                                                                                                                                                                                         
@Component                                                                                                                                                                                               
public class ExecutionGate {                                                                                                                                                                             
    public void check(Resource resource) {                                                                                                                                                               
        if ("HIGH".equalsIgnoreCase(resource.getSensitivity())) {                                                                                                                                        
            throw new SecurityException("权限不足：拒绝执行高敏感度工具 [" + resource.getName() + "]");                                                                                                  
        }                                                                                                                                                                                                
        if (Boolean.TRUE.equals(resource.getRequiresApproval())) {                                                                                                                                       
            // 拋出特定異常或返回特定狀態，交由 SkillExecutor 處理審批流                                                                                                                                 
            throw new ApprovalRequiredException("工具 [" + resource.getName() + "] 需要人工审批");                                                                                                       
        }                                                                                                                                                                                                
    }                                                                                                                                                                                                    
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

3.4.2 工具執行器 (Tool Executor) - 核心反射引擎                                                                                                                                                          

取代原有的 @Tool 掃描，通過數據庫中的 beanName 和 methodName 動態調用。                                                                                                                                  

                                                                                                                                                                                                         
@Component                                                                                                                                                                                               
@RequiredArgsConstructor                                                                                                                                                                                 
public class ToolExecutor {                                                                                                                                                                              
                                                                                                                                                                                                         
    private final ApplicationContext applicationContext;                                                                                                                                                 
    private final ObjectMapper objectMapper;                                                                                                                                                             
                                                                                                                                                                                                         
    public String execute(Resource tool, String argsJson) {                                                                                                                                              
        try {                                                                                                                                                                                            
            // 1. 從 Spring 容器獲取目標 Bean (例如 "searchTools")                                                                                                                                       
            Object bean = applicationContext.getBean(tool.getBeanName());                                                                                                                                
                                                                                                                                                                                                         
            // 2. 獲取目標方法 (例如 "web_search")                                                                                                                                                       
            Method[] methods = bean.getClass().getMethods();                                                                                                                                             
            Method targetMethod = null;                                                                                                                                                                  
            for (Method m : methods) {                                                                                                                                                                   
                if (m.getName().equals(tool.getMethodName())) {                                                                                                                                          
                    targetMethod = m;                                                                                                                                                                    
                    break;                                                                                                                                                                               
                }                                                                                                                                                                                        
            }                                                                                                                                                                                            
            if (targetMethod == null) throw new NoSuchMethodException("未找到方法: " + tool.getMethodName());                                                                                            
                                                                                                                                                                                                         
            // 3. 參數綁定：將 JSON 字符串解析為方法所需的 Object[]                                                                                                                                      
            Object[] args = resolveArgs(targetMethod, argsJson);                                                                                                                                         
                                                                                                                                                                                                         
            // 4. 反射執行                                                                                                                                                                               
            Object result = targetMethod.invoke(bean, args);                                                                                                                                             
                                                                                                                                                                                                         
            // 5. 序列化返回結果                                                                                                                                                                         
            return objectMapper.writeValueAsString(result);                                                                                                                                              
        } catch (Exception e) {                                                                                                                                                                          
            return "{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}";                                                                                                                     
        }                                                                                                                                                                                                
    }                                                                                                                                                                                                    
                                                                                                                                                                                                         
    private Object[] resolveArgs(Method method, String argsJson) throws Exception {                                                                                                                      
        JsonNode jsonNode = objectMapper.readTree(argsJson);                                                                                                                                             
        Parameter[] parameters = method.getParameters();                                                                                                                                                 
        Object[] args = new Object[parameters.length];                                                                                                                                                   
                                                                                                                                                                                                         
        for (int i = 0; i < parameters.length; i++) {                                                                                                                                                    
            String paramName = parameters[i].getName(); // 需確保編譯時開啟了 -parameters 參數                                                                                                           
            if (jsonNode.has(paramName)) {                                                                                                                                                               
                args[i] = objectMapper.treeToValue(jsonNode.get(paramName), parameters[i].getType());                                                                                                    
            } else {                                                                                                                                                                                     
                args[i] = null;                                                                                                                                                                          
            }                                                                                                                                                                                            
        }                                                                                                                                                                                                
        return args;                                                                                                                                                                                     
    }                                                                                                                                                                                                    
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

3.4.3 技能執行器 (Skill Executor)                                                                                                                                                                        

處理複雜、異步或需要審批的流程。                                                                                                                                                                         

                                                                                                                                                                                                         
@Component                                                                                                                                                                                               
public class SkillExecutor {                                                                                                                                                                             
    public String execute(Resource skill, String argsJson) {                                                                                                                                             
        // 1. 處理審批邏輯                                                                                                                                                                               
        if (Boolean.TRUE.equals(skill.getRequiresApproval())) {                                                                                                                                          
            System.out.println("【系统提示】任务 [" + skill.getName() + "] 等待审批...");                                                                                                                
            // 模擬審批通過                                                                                                                                                                              
            System.out.println("【系统提示】审批已自动通过。");                                                                                                                                          
        }                                                                                                                                                                                                
                                                                                                                                                                                                         
        // 2. 處理異步邏輯                                                                                                                                                                               
        if ("ASYNC".equalsIgnoreCase(skill.getRunMode())) {                                                                                                                                              
            String taskId = UUID.randomUUID().toString();                                                                                                                                                
            // 提交到線程池或消息隊列 (RocketMQ)                                                                                                                                                         
            // ...                                                                                                                                                                                       
            return "{\"status\":\"pending\", \"taskId\":\"" + taskId + "\", \"message\":\"异步任务已提交\"}";                                                                                            
        }                                                                                                                                                                                                
                                                                                                                                                                                                         
        // 3. 同步執行邏輯 (可複用 ToolExecutor 或調用特定 Skill Bean)                                                                                                                                   
        return "{\"status\":\"success\", \"data\":\"Skill 执行完毕\"}";                                                                                                                                  
    }                                                                                                                                                                                                    
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

3.5 編排核心 (luna-agent-orchestrator)                                                                                                                                                                   

系統的「大腦」，將上述組件串聯起來。                                                                                                                                                                     

                                                                                                                                                                                                         
@Service                                                                                                                                                                                                 
@RequiredArgsConstructor                                                                                                                                                                                 
public class AgentService {                                                                                                                                                                              
                                                                                                                                                                                                         
    private final ToolRouter toolRouter;                                                                                                                                                                 
    private final LlmAdapter llmAdapter;                                                                                                                                                                 
    private final ExecutionGate executionGate;                                                                                                                                                           
    private final ToolExecutor toolExecutor;                                                                                                                                                             
    private final SkillExecutor skillExecutor;                                                                                                                                                           
                                                                                                                                                                                                         
    public String handleUserInput(String input) {                                                                                                                                                        
        // 1. 獲取候選工具                                                                                                                                                                               
        List<Resource> candidates = toolRouter.findCandidates(input);                                                                                                                                    
                                                                                                                                                                                                         
        // 2. 決策階段 (調用 LLM 判斷是否需要工具)                                                                                                                                                       
        String decisionPrompt = buildDecisionPrompt(input, candidates);                                                                                                                                  
        String decisionJson = llmAdapter.generate(decisionPrompt);                                                                                                                                       
        // 解析 decisionJson 獲取 toolName...                                                                                                                                                            
                                                                                                                                                                                                         
        if (/* 需要調用工具 */) {                                                                                                                                                                        
            Resource targetResource = findResourceByName(candidates, toolName);                                                                                                                          
                                                                                                                                                                                                         
            // 3. 參數生成階段                                                                                                                                                                           
            String argsPrompt = buildArgsPrompt(input, targetResource.getInputSchema());                                                                                                                 
            String argsJson = llmAdapter.generate(argsPrompt);                                                                                                                                           
                                                                                                                                                                                                         
            // 4. JSON Schema 校驗                                                                                                                                                                       
            boolean isValid = JsonSchemaValidator.validate(targetResource.getInputSchema(), argsJson);                                                                                                   
            if (!isValid) {                                                                                                                                                                              
                // 觸發修復邏輯 (Self-Correction)                                                                                                                                                        
                argsJson = llmAdapter.generate("参数校验失败，请根据 Schema 重新生成: " + argsJson);                                                                                                     
            }                                                                                                                                                                                            
                                                                                                                                                                                                         
            // 5. 權限與審批網關                                                                                                                                                                         
            try {                                                                                                                                                                                        
                executionGate.check(targetResource);                                                                                                                                                     
            } catch (ApprovalRequiredException e) {                                                                                                                                                      
                return skillExecutor.execute(targetResource, argsJson); // 轉交 Skill 處理審批                                                                                                           
            }                                                                                                                                                                                            
                                                                                                                                                                                                         
            // 6. 執行工具                                                                                                                                                                               
            String toolResult;                                                                                                                                                                           
            if ("SKILL".equals(targetResource.getType())) {                                                                                                                                              
                toolResult = skillExecutor.execute(targetResource, argsJson);                                                                                                                            
            } else {                                                                                                                                                                                     
                toolResult = toolExecutor.execute(targetResource, argsJson);                                                                                                                             
            }                                                                                                                                                                                            
                                                                                                                                                                                                         
            // 7. 將工具結果再次餵給 LLM 生成最終自然語言回復                                                                                                                                            
            String finalPrompt = buildFinalPrompt(input, toolResult);                                                                                                                                    
            return llmAdapter.generate(finalPrompt);                                                                                                                                                     
        }                                                                                                                                                                                                
                                                                                                                                                                                                         
        // 不需要工具，直接對話                                                                                                                                                                          
        return llmAdapter.generate(input);                                                                                                                                                               
    }                                                                                                                                                                                                    
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


4. 數據庫設計 (DDL)                                                                                                                                                                                      

4.1 資源表 (resources)                                                                                                                                                                                   

                                                                                                                                                                                                         
CREATE TABLE resources (                                                                                                                                                                                 
    id VARCHAR(64) PRIMARY KEY,                                                                                                                                                                          
    type VARCHAR(20) NOT NULL COMMENT 'TOOL 或 SKILL',                                                                                                                                                   
    name VARCHAR(100) NOT NULL UNIQUE COMMENT '工具唯一名稱',                                                                                                                                            
    description TEXT COMMENT '工具語義描述',                                                                                                                                                             
    version VARCHAR(20) DEFAULT '1.0.0',                                                                                                                                                                 
    owner VARCHAR(50),                                                                                                                                                                                   
                                                                                                                                                                                                         
    bean_name VARCHAR(100) NOT NULL COMMENT 'Spring Bean 名稱',                                                                                                                                          
    method_name VARCHAR(100) NOT NULL COMMENT '執行方法名稱',                                                                                                                                            
                                                                                                                                                                                                         
    input_schema TEXT COMMENT '參數 JSON Schema',                                                                                                                                                        
    output_schema TEXT COMMENT '輸出 JSON Schema',                                                                                                                                                       
                                                                                                                                                                                                         
    run_mode VARCHAR(20) DEFAULT 'SYNC' COMMENT 'SYNC 或 ASYNC',                                                                                                                                         
    requires_approval BOOLEAN DEFAULT FALSE COMMENT '是否需要審批',                                                                                                                                      
    sensitivity VARCHAR(20) DEFAULT 'LOW' COMMENT 'LOW, MEDIUM, HIGH',                                                                                                                                   
                                                                                                                                                                                                         
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,                                                                                                                                                      
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP                                                                                                                           
);                                                                                                                                                                                                       
                                                                                                                                                                                                         

4.2 任務表 (tasks)                                                                                                                                                                                       

                                                                                                                                                                                                         
CREATE TABLE tasks (                                                                                                                                                                                     
    task_id VARCHAR(64) PRIMARY KEY,                                                                                                                                                                     
    resource_id VARCHAR(64) NOT NULL,                                                                                                                                                                    
    status VARCHAR(20) NOT NULL COMMENT 'PENDING, RUNNING, COMPLETED, REJECTED, PENDING_APPROVAL',                                                                                                       
    input_args TEXT COMMENT '執行參數',                                                                                                                                                                  
    result TEXT COMMENT '執行結果',                                                                                                                                                                      
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,                                                                                                                                                      
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP                                                                                                                           
);                                                                                                                                                                                                       
                                                                                                                                                                                                         

4.3 日誌表 (logs)                                                                                                                                                                                        

(沿用並優化現有的 luna_log 表)                                                                                                                                                                           

                                                                                                                                                                                                         
CREATE TABLE logs (                                                                                                                                                                                      
    id BIGINT AUTO_INCREMENT PRIMARY KEY,                                                                                                                                                                
    log_type VARCHAR(50),                                                                                                                                                                                
    module VARCHAR(50),                                                                                                                                                                                  
    action VARCHAR(50),                                                                                                                                                                                  
    content TEXT,                                                                                                                                                                                        
    request_data JSON,                                                                                                                                                                                   
    response_data JSON,                                                                                                                                                                                  
    error_message TEXT,                                                                                                                                                                                  
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP                                                                                                                                                       
);                                                                                                                                                                                                       
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


5. 完整調用鏈路 (Sequence)                                                                                                                                                                               

  1 User 發送消息 ChatRequest。                                                                                                                                                                          
  2 AgentService 接收請求，調用 ToolRouter。                                                                                                                                                             
  3 ToolRouter 查詢 MCP Server (數據庫)，返回候選 Resource 列表。                                                                                                                                        
  4 AgentService 構造 Prompt，調用 LlmAdapter 進行【決策】。                                                                                                                                             
  5 LlmAdapter 返回決定調用的 tool_name。                                                                                                                                                                
  6 AgentService 再次調用 LlmAdapter 生成【參數 JSON】。                                                                                                                                                 
  7 AgentService 調用 JsonSchemaValidator 校驗參數。                                                                                                                                                     
     • 若失敗：觸發 LLM 修復。                                                                                                                                                                           
  8 AgentService 調用 ExecutionGate 檢查權限與審批。                                                                                                                                                     
  9 AgentService 將 Resource 和 argsJson 傳遞給 ToolExecutor。                                                                                                                                           
 10 ToolExecutor 通過反射調用具體的 Spring Bean (如 SearchTools.web_search)。                                                                                                                            
 11 ToolExecutor 返回 JSON 結果。                                                                                                                                                                        
 12 AgentService 將結果拼接進 Prompt，最後一次調用 LlmAdapter 生成最終回復。                                                                                                                             
 13 返回結果給 User。                                                                                                                                                                                    

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


6. 實施與遷移路徑                                                                                                                                                                                        

Phase 1: 基礎設施重構                                                                                                                                                                                    

 • 創建 Maven 多模組結構。                                                                                                                                                                               
 • 將 org.yilena.luna.entity 遷移至 luna-common。                                                                                                                                                        
 • 執行 SQL 腳本創建 resources 和 tasks 表。                                                                                                                                                             

Phase 2: 數據驅動改造 (去除 @Tool)                                                                                                                                                                       

 • 刪除 SearchTools, MemoryTools, ScheduleTools 等類中的 @Tool 註解。                                                                                                                                    
 • 編寫 SQL 初始化腳本，將這些工具的元數據（特別是 input_schema、bean_name、method_name）手動插入到 resources 表中。                                                                                     

Phase 3: 核心引擎開發                                                                                                                                                                                    

 • 實現 luna-mcp-server 的 CRUD 接口。                                                                                                                                                                   
 • 實現 luna-tool-executor 中的 ReflectionToolExecutor。                                                                                                                                                 
 • 實現 luna-agent-orchestrator 中的 AgentService 完整編排邏輯。                                                                                                                                         

Phase 4: 測試與切換                                                                                                                                                                                      

 • 使用 MockLlmAdapter 進行本地全鏈路測試，確保反射調用和參數綁定正確無誤。                                                                                                                              
 • 替換原有的 ChatServiceImpl 邏輯，正式上線 v2.0 架構。