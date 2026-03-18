-- MCP 資源表：存儲工具與技能的元數據
CREATE TABLE IF NOT EXISTS resources (
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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 任務表：用於異步任務或審批流
CREATE TABLE IF NOT EXISTS tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'PENDING, RUNNING, COMPLETED, REJECTED, PENDING_APPROVAL',
    input_args TEXT COMMENT '執行參數',
    result TEXT COMMENT '執行結果',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 初始化核心工具數據 (手動註冊)
-- 注意：input_schema 需根據實際代碼參數進行嚴格定義，此處為示例

-- 1. 網頁搜索
INSERT INTO resources (id, type, name, description, bean_name, method_name, input_schema)
VALUES (
    'tool_web_search', 'TOOL', 'web_search', 
    '通用网页搜索工具。当用户明确要求搜索、查询，或者问题超出知识范围时调用。', 
    'searchTools', 'web_search', 
    '{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"]}'
) ON CONFLICT (name) DO NOTHING;

-- 2. 圖片搜索
INSERT INTO resources (id, type, name, description, bean_name, method_name, input_schema)
VALUES (
    'tool_image_search', 'TOOL', 'image_search', 
    '图片搜索工具。当用户明确要求找图、看图时调用。', 
    'searchTools', 'image_search', 
    '{"type":"object","properties":{"query":{"type":"string","description":"图片关键词"}},"required":["query"]}'
) ON CONFLICT (name) DO NOTHING;

-- 3. 新聞搜索
INSERT INTO resources (id, type, name, description, bean_name, method_name, input_schema)
VALUES (
    'tool_news_search', 'TOOL', 'news_search', 
    '新闻搜索工具。当用户询问新闻、热点时调用。', 
    'searchTools', 'news_search', 
    '{"type":"object","properties":{"query":{"type":"string","description":"新闻关键词"}},"required":["query"]}'
) ON CONFLICT (name) DO NOTHING;

-- 4. 記憶管理
INSERT INTO resources (id, type, name, description, bean_name, method_name, input_schema)
VALUES (
    'tool_manage_memory', 'TOOL', 'manage_memory', 
    '长期记忆管理工具。用于增删改查用户的长期记忆。', 
    'memoryTools', 'manageMemory', 
    '{"type":"object","properties":{"action":{"type":"string","enum":["INSERT","UPDATE","DELETE","QUERY"]},"content":{"type":"string"},"memoryType":{"type":"string"}},"required":["action"]}'
) ON CONFLICT (name) DO NOTHING;

-- 5. 日程管理
INSERT INTO resources (id, type, name, description, bean_name, method_name, input_schema)
VALUES (
    'tool_manage_schedule', 'TOOL', 'manage_schedule', 
    '日程任务管理工具。用于设置提醒或待办事项。', 
    'scheduleTools', 'manageScheduleTask', 
    '{"type":"object","properties":{"action":{"type":"string","enum":["INSERT","UPDATE","DELETE","QUERY"]},"content":{"type":"string"},"triggerTime":{"type":"string"}},"required":["action"]}'
) ON CONFLICT (name) DO NOTHING;
