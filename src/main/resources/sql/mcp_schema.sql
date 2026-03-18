-- 刪除舊表 (如果存在)
DROP TABLE IF EXISTS resources;

-- 1. MCP 工具表 (Atomic Tools)
-- 存儲原子工具，通常是無狀態、同步執行的
CREATE TABLE IF NOT EXISTS mcp_tools (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE COMMENT '工具唯一名稱',
    description TEXT COMMENT '工具語義描述',
    version VARCHAR(20) DEFAULT '1.0.0',
    owner VARCHAR(50),
    
    bean_name VARCHAR(100) NOT NULL COMMENT 'Spring Bean 名稱',
    method_name VARCHAR(100) NOT NULL COMMENT '執行方法名稱',
    
    input_schema TEXT COMMENT '參數 JSON Schema',
    output_schema TEXT COMMENT '輸出 JSON Schema',
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. MCP 技能表 (Composite Skills)
-- 存儲複合技能，支持異步、審批、高敏感權限控制
CREATE TABLE IF NOT EXISTS mcp_skills (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE COMMENT '技能唯一名稱',
    description TEXT COMMENT '技能語義描述',
    version VARCHAR(20) DEFAULT '1.0.0',
    owner VARCHAR(50),
    
    bean_name VARCHAR(100) NOT NULL COMMENT 'Spring Bean 名稱 (或工作流引擎ID)',
    method_name VARCHAR(100) NOT NULL COMMENT '執行方法名稱',
    
    input_schema TEXT COMMENT '參數 JSON Schema',
    output_schema TEXT COMMENT '輸出 JSON Schema',
    
    run_mode VARCHAR(20) DEFAULT 'SYNC' COMMENT 'SYNC 或 ASYNC',
    requires_approval BOOLEAN DEFAULT FALSE COMMENT '是否需要審批',
    sensitivity VARCHAR(20) DEFAULT 'LOW' COMMENT 'LOW, MEDIUM, HIGH',
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 任務表 (保持不變)
CREATE TABLE IF NOT EXISTS tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'PENDING, RUNNING, COMPLETED, REJECTED, PENDING_APPROVAL',
    input_args TEXT COMMENT '執行參數',
    result TEXT COMMENT '執行結果',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 初始化數據：工具 (插入 mcp_tools)
INSERT INTO mcp_tools (id, name, description, bean_name, method_name, input_schema)
VALUES 
(
    'tool_web_search', 'web_search', 
    '通用网页搜索工具。当用户明确要求搜索、查询，或者问题超出知识范围时调用。', 
    'searchTools', 'web_search', 
    '{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"]}'
),
(
    'tool_image_search', 'image_search', 
    '图片搜索工具。当用户明确要求找图、看图时调用。', 
    'searchTools', 'image_search', 
    '{"type":"object","properties":{"query":{"type":"string","description":"图片关键词"}},"required":["query"]}'
),
(
    'tool_news_search', 'news_search', 
    '新闻搜索工具。当用户询问新闻、热点时调用。', 
    'searchTools', 'news_search', 
    '{"type":"object","properties":{"query":{"type":"string","description":"新闻关键词"}},"required":["query"]}'
),
(
    'tool_manage_memory', 'manage_memory', 
    '长期记忆管理工具。用于增删改查用户的长期记忆。', 
    'memoryTools', 'manageMemory', 
    '{"type":"object","properties":{"action":{"type":"string","enum":["INSERT","UPDATE","DELETE","QUERY"]},"content":{"type":"string"},"memoryType":{"type":"string"}},"required":["action"]}'
),
(
    'tool_manage_schedule', 'manage_schedule', 
    '日程任务管理工具。用于设置提醒或待办事项。', 
    'scheduleTools', 'manageScheduleTask', 
    '{"type":"object","properties":{"action":{"type":"string","enum":["INSERT","UPDATE","DELETE","QUERY"]},"content":{"type":"string"},"triggerTime":{"type":"string"}},"required":["action"]}'
) ON CONFLICT (name) DO NOTHING;

-- 初始化數據：技能 (插入 mcp_skills)
-- 示例：一個高敏感度的數據導出技能
INSERT INTO mcp_skills (id, name, description, bean_name, method_name, input_schema, run_mode, requires_approval, sensitivity)
VALUES 
(
    'skill_export_data', 'export_user_data',
    '导出用户所有数据。这是一个高风险操作，需要审批。',
    'dataExportSkill', 'execute',
    '{"type":"object","properties":{"userId":{"type":"string"}},"required":["userId"]}',
    'ASYNC', TRUE, 'HIGH'
) ON CONFLICT (name) DO NOTHING;
