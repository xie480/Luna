# MCP 工具註冊清單 (JSON 格式)

以下是所有工具的 JSON 定義。你可以直接複製每個代碼塊中的 JSON 對象進行註冊。

> **提示**：`inputSchema` 和 `outputSchema` 在此處展示為嵌套對象 (Object) 以便於閱讀和編輯。如果你的後端接口要求這兩個字段必須是 String 類型，請在前端發送請求前將其轉換為字符串 (JSON.stringify)。
                                                                                                                                                                                                         
---                                                                                                                                                                                                      

## 1. 用戶偏好設置 (PreferenceTools)

```json                                                                                                                                                                                                  
{                                                                                                                                                                                                        
  "name": "manage_user_preference",                                                                                                                                                                      
  "description": "管理用戶偏好設置，支持 INSERT(新增), QUERY(查詢), UPDATE(修改), DELETE(刪除) 操作。",                                                                                                  
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "preferenceTools",                                                                                                                                                                         
  "methodName": "manageUserPreference",                                                                                                                                                                  
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "action": {                                                                                                                                                                                        
        "type": "string",                                                                                                                                                                                
        "enum": ["INSERT", "QUERY", "UPDATE", "DELETE"],                                                                                                                                                 
        "description": "操作類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "id": {                                                                                                                                                                                            
        "type": "integer",                                                                                                                                                                               
        "description": "記錄ID (UPDATE/DELETE 操作必須)"                                                                                                                                                 
      },                                                                                                                                                                                                 
      "mode": {                                                                                                                                                                                          
        "type": "string",                                                                                                                                                                                
        "enum": ["PUT", "PATCH"],                                                                                                                                                                        
        "description": "更新模式 (PUT:全量替換, PATCH:部分更新)"                                                                                                                                         
      },                                                                                                                                                                                                 
      "prefKey": {                                                                                                                                                                                       
        "type": "string",                                                                                                                                                                                
        "description": "偏好設置的鍵名 (Key)"                                                                                                                                                            
      },                                                                                                                                                                                                 
      "prefValue": {                                                                                                                                                                                     
        "type": "string",                                                                                                                                                                                
        "description": "偏好設置的值 (Value)"                                                                                                                                                            
      },                                                                                                                                                                                                 
      "description": {                                                                                                                                                                                   
        "type": "string",                                                                                                                                                                                
        "description": "備註描述"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "hardDelete": {                                                                                                                                                                                    
        "type": "boolean",                                                                                                                                                                               
        "description": "是否物理刪除 (僅 DELETE 操作有效)"                                                                                                                                               
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["action"]                                                                                                                                                                               
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "status": { "type": "string", "description": "success 或 error" },                                                                                                                                 
      "data": { "type": "object", "description": "UserPreference 對象或列表" }                                                                                                                           
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


2. 網頁搜索 (SearchTools - web_search)                                                                                                                                                                   

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "web_search",                                                                                                                                                                                  
  "description": "執行互聯網網頁搜索，獲取最新的信息和數據。",                                                                                                                                           
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "searchTools",                                                                                                                                                                             
  "methodName": "web_search",                                                                                                                                                                            
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "query": {                                                                                                                                                                                         
        "type": "string",                                                                                                                                                                                
        "description": "搜索關鍵詞"                                                                                                                                                                      
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["query"]                                                                                                                                                                                
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "organic": { "type": "array", "description": "搜索結果列表" },                                                                                                                                     
      "peopleAlsoAsk": { "type": "array", "description": "相關問題" }                                                                                                                                    
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


3. 圖片搜索 (SearchTools - image_search)                                                                                                                                                                 

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "image_search",                                                                                                                                                                                
  "description": "搜索互聯網上的圖片資源。",                                                                                                                                                             
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "searchTools",                                                                                                                                                                             
  "methodName": "image_search",                                                                                                                                                                          
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "query": {                                                                                                                                                                                         
        "type": "string",                                                                                                                                                                                
        "description": "圖片搜索關鍵詞"                                                                                                                                                                  
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["query"]                                                                                                                                                                                
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "images": { "type": "array", "description": "圖片結果列表，包含 URL 和標題" }                                                                                                                      
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


4. 新聞搜索 (SearchTools - news_search)                                                                                                                                                                  

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "news_search",                                                                                                                                                                                 
  "description": "搜索最新的新聞報道和時事資訊。",                                                                                                                                                       
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "searchTools",                                                                                                                                                                             
  "methodName": "news_search",                                                                                                                                                                           
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "query": {                                                                                                                                                                                         
        "type": "string",                                                                                                                                                                                
        "description": "新聞搜索關鍵詞"                                                                                                                                                                  
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["query"]                                                                                                                                                                                
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "news": { "type": "array", "description": "新聞結果列表，包含標題、來源、時間" }                                                                                                                   
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


5. 以圖搜圖 (SearchTools - lens_search)                                                                                                                                                                  

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "lens_search",                                                                                                                                                                                 
  "description": "通過圖片 URL 進行以圖搜圖 (Google Lens)。",                                                                                                                                            
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "searchTools",                                                                                                                                                                             
  "methodName": "lens_search",                                                                                                                                                                           
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "url": {                                                                                                                                                                                           
        "type": "string",                                                                                                                                                                                
        "description": "圖片的 URL 地址"                                                                                                                                                                 
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["url"]                                                                                                                                                                                  
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "description": "Google Lens 識別結果"                                                                                                                                                                
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


6. 網頁抓取 (SearchTools - web_scrape)                                                                                                                                                                   

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "web_scrape",                                                                                                                                                                                  
  "description": "抓取並提取指定網頁 URL 的文本內容。",                                                                                                                                                  
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "searchTools",                                                                                                                                                                             
  "methodName": "web_scrape",                                                                                                                                                                            
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "url": {                                                                                                                                                                                           
        "type": "string",                                                                                                                                                                                
        "description": "目標網頁 URL"                                                                                                                                                                    
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["url"]                                                                                                                                                                                  
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "text": { "type": "string", "description": "網頁純文本內容" },                                                                                                                                     
      "metadata": { "type": "object", "description": "網頁元數據" }                                                                                                                                      
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


7. 日程任務管理 (ScheduleTools)                                                                                                                                                                          

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "manage_schedule_task",                                                                                                                                                                        
  "description": "管理日程和定時任務，支持創建、查詢、修改和刪除任務。",                                                                                                                                 
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "scheduleTools",                                                                                                                                                                           
  "methodName": "manageScheduleTask",                                                                                                                                                                    
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "action": {                                                                                                                                                                                        
        "type": "string",                                                                                                                                                                                
        "enum": ["INSERT", "QUERY", "UPDATE", "DELETE"],                                                                                                                                                 
        "description": "操作類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "id": {                                                                                                                                                                                            
        "type": "integer",                                                                                                                                                                               
        "description": "任務ID (UPDATE/DELETE 操作必須)"                                                                                                                                                 
      },                                                                                                                                                                                                 
      "mode": {                                                                                                                                                                                          
        "type": "string",                                                                                                                                                                                
        "enum": ["PUT", "PATCH"],                                                                                                                                                                        
        "description": "更新模式"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "content": {                                                                                                                                                                                       
        "type": "string",                                                                                                                                                                                
        "description": "任務內容"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "triggerTime": {                                                                                                                                                                                   
        "type": "string",                                                                                                                                                                                
        "description": "觸發時間，格式: yyyy-MM-dd HH:mm:ss"                                                                                                                                             
      },                                                                                                                                                                                                 
      "status": {                                                                                                                                                                                        
        "type": "string",                                                                                                                                                                                
        "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"],                                                                                                                              
        "description": "任務狀態"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "taskType": {                                                                                                                                                                                      
        "type": "string",                                                                                                                                                                                
        "enum": ["ONCE", "CRON", "INTERVAL"],                                                                                                                                                            
        "description": "任務類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "hardDelete": {                                                                                                                                                                                    
        "type": "boolean",                                                                                                                                                                               
        "description": "是否物理刪除"                                                                                                                                                                    
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["action"]                                                                                                                                                                               
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "status": { "type": "string" },                                                                                                                                                                    
      "data": { "type": "object", "description": "ScheduleTask 對象或列表" }                                                                                                                             
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


8. 知識庫管理 (KnowledgeBaseTools)                                                                                                                                                                       

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "manage_knowledge_base",                                                                                                                                                                       
  "description": "管理知識庫，支持寫入新知識 (INSERT) 和語義檢索 (QUERY)。",                                                                                                                             
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "knowledgeBaseTools",                                                                                                                                                                      
  "methodName": "manageKnowledgeBase",                                                                                                                                                                   
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "action": {                                                                                                                                                                                        
        "type": "string",                                                                                                                                                                                
        "enum": ["INSERT", "QUERY"],                                                                                                                                                                     
        "description": "操作類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "title": {                                                                                                                                                                                         
        "type": "string",                                                                                                                                                                                
        "description": "知識標題 (INSERT 必須)"                                                                                                                                                          
      },                                                                                                                                                                                                 
      "content": {                                                                                                                                                                                       
        "type": "string",                                                                                                                                                                                
        "description": "知識內容文本 (INSERT 必須)"                                                                                                                                                      
      },                                                                                                                                                                                                 
      "sourceType": {                                                                                                                                                                                    
        "type": "string",                                                                                                                                                                                
        "enum": ["TEXT", "FILE", "URL"],                                                                                                                                                                 
        "description": "來源類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "sourcePath": {                                                                                                                                                                                    
        "type": "string",                                                                                                                                                                                
        "description": "來源路徑或鏈接"                                                                                                                                                                  
      },                                                                                                                                                                                                 
      "query": {                                                                                                                                                                                         
        "type": "string",                                                                                                                                                                                
        "description": "檢索關鍵詞 (QUERY 必須)"                                                                                                                                                         
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["action"]                                                                                                                                                                               
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "status": { "type": "string" },                                                                                                                                                                    
      "data": { "type": "array", "description": "檢索到的知識片段列表或操作結果消息" }                                                                                                                   
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


9. 長期記憶管理 (MemoryTools)                                                                                                                                                                            

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "manage_memory",                                                                                                                                                                               
  "description": "管理 AI 的長期記憶，包括存儲、查詢、更新和刪除記憶片段。",                                                                                                                             
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "memoryTools",                                                                                                                                                                             
  "methodName": "manageMemory",                                                                                                                                                                          
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "action": {                                                                                                                                                                                        
        "type": "string",                                                                                                                                                                                
        "enum": ["INSERT", "QUERY", "UPDATE", "DELETE"],                                                                                                                                                 
        "description": "操作類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "id": {                                                                                                                                                                                            
        "type": "integer",                                                                                                                                                                               
        "description": "記憶ID (UPDATE/DELETE 必須)"                                                                                                                                                     
      },                                                                                                                                                                                                 
      "mode": {                                                                                                                                                                                          
        "type": "string",                                                                                                                                                                                
        "enum": ["PUT", "PATCH"],                                                                                                                                                                        
        "description": "更新模式"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "sessionId": {                                                                                                                                                                                     
        "type": "string",                                                                                                                                                                                
        "description": "會話標識 ID"                                                                                                                                                                     
      },                                                                                                                                                                                                 
      "memoryType": {                                                                                                                                                                                    
        "type": "string",                                                                                                                                                                                
        "enum": ["USER_PROFILE", "CONVERSATION_SUMMARY", "FACT", "PREFERENCE"],                                                                                                                          
        "description": "記憶類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "content": {                                                                                                                                                                                       
        "type": "string",                                                                                                                                                                                
        "description": "記憶內容"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "weight": {                                                                                                                                                                                        
        "type": "integer",                                                                                                                                                                               
        "description": "記憶權重 (默認1)"                                                                                                                                                                
      },                                                                                                                                                                                                 
      "hardDelete": {                                                                                                                                                                                    
        "type": "boolean",                                                                                                                                                                               
        "description": "是否物理刪除"                                                                                                                                                                    
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["action"]                                                                                                                                                                               
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "status": { "type": "string" },                                                                                                                                                                    
      "data": { "type": "object", "description": "Memory 對象或列表" }                                                                                                                                   
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}                                                                                                                                                                                                        
                                                                                                                                                                                                         

---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


10. 系統日誌管理 (LogTools)                                                                                                                                                                              

                                                                                                                                                                                                         
{                                                                                                                                                                                                        
  "name": "manage_log",                                                                                                                                                                                  
  "description": "查詢或管理系統運行日誌，支持按時間、模塊、類型篩選。",                                                                                                                                 
  "version": "1.0.0",                                                                                                                                                                                    
  "owner": "System",                                                                                                                                                                                     
  "beanName": "logTools",                                                                                                                                                                                
  "methodName": "manageLog",                                                                                                                                                                             
  "inputSchema": {                                                                                                                                                                                       
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "action": {                                                                                                                                                                                        
        "type": "string",                                                                                                                                                                                
        "enum": ["INSERT", "QUERY", "DELETE"],                                                                                                                                                           
        "description": "操作類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "logType": {                                                                                                                                                                                       
        "type": "string",                                                                                                                                                                                
        "enum": ["SYSTEM_EVENT", "USER_ACTION", "ERROR", "TOOL_CALL"],                                                                                                                                   
        "description": "日誌類型"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "module": {                                                                                                                                                                                        
        "type": "string",                                                                                                                                                                                
        "description": "所屬模塊"                                                                                                                                                                        
      },                                                                                                                                                                                                 
      "content": {                                                                                                                                                                                       
        "type": "string",                                                                                                                                                                                
        "description": "日誌內容 (INSERT 用)"                                                                                                                                                            
      },                                                                                                                                                                                                 
      "startTime": {                                                                                                                                                                                     
        "type": "string",                                                                                                                                                                                
        "description": "查詢開始時間 (yyyy-MM-dd HH:mm:ss)"                                                                                                                                              
      },                                                                                                                                                                                                 
      "endTime": {                                                                                                                                                                                       
        "type": "string",                                                                                                                                                                                
        "description": "查詢結束時間 (yyyy-MM-dd HH:mm:ss)"                                                                                                                                              
      },                                                                                                                                                                                                 
      "limit": {                                                                                                                                                                                         
        "type": "integer",                                                                                                                                                                               
        "description": "返回條數限制 (默認10)"                                                                                                                                                           
      },                                                                                                                                                                                                 
      "id": {                                                                                                                                                                                            
        "type": "integer",                                                                                                                                                                               
        "description": "日誌ID (DELETE 用)"                                                                                                                                                              
      },                                                                                                                                                                                                 
      "beforeTime": {                                                                                                                                                                                    
        "type": "string",                                                                                                                                                                                
        "description": "刪除此時間之前的日誌 (DELETE 用)"                                                                                                                                                
      }                                                                                                                                                                                                  
    },                                                                                                                                                                                                   
    "required": ["action"]                                                                                                                                                                               
  },                                                                                                                                                                                                     
  "outputSchema": {                                                                                                                                                                                      
    "type": "object",                                                                                                                                                                                    
    "properties": {                                                                                                                                                                                      
      "status": { "type": "string" },                                                                                                                                                                    
      "data": { "type": "object", "description": "LunaLog 對象列表或操作消息" }                                                                                                                          
    }                                                                                                                                                                                                    
  }                                                                                                                                                                                                      
}