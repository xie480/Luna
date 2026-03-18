-- 刪除舊表 (如果存在)
DROP TABLE IF EXISTS resources;
DROP TABLE IF EXISTS mcp_tools;
DROP TABLE IF EXISTS mcp_skills;
DROP TABLE IF EXISTS tasks;

-- 1. MCP 工具表 (Atomic Tools)
-- 存儲原子工具，通常是無狀態、同步執行的
CREATE TABLE IF NOT EXISTS mcp_tools (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    version VARCHAR(20) DEFAULT '1.0.0',
    owner VARCHAR(50),
    
    bean_name VARCHAR(100) NOT NULL,
    method_name VARCHAR(100) NOT NULL,
    
    input_schema TEXT,
    output_schema TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE mcp_tools IS 'MCP 工具表 (Atomic Tools) - 存儲原子工具，通常是無狀態、同步執行的';
COMMENT ON COLUMN mcp_tools.name IS '工具唯一名稱';
COMMENT ON COLUMN mcp_tools.description IS '工具語義描述';
COMMENT ON COLUMN mcp_tools.bean_name IS 'Spring Bean 名稱';
COMMENT ON COLUMN mcp_tools.method_name IS '執行方法名稱';
COMMENT ON COLUMN mcp_tools.input_schema IS '參數 JSON Schema';
COMMENT ON COLUMN mcp_tools.output_schema IS '輸出 JSON Schema';

-- 2. MCP 技能表 (Composite Skills)
-- 存儲複合技能，支持異步、審批、高敏感權限控制
CREATE TABLE IF NOT EXISTS mcp_skills (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    version VARCHAR(20) DEFAULT '1.0.0',
    owner VARCHAR(50),
    
    bean_name VARCHAR(100) NOT NULL,
    method_name VARCHAR(100) NOT NULL,
    
    input_schema TEXT,
    output_schema TEXT,
    
    run_mode VARCHAR(20) DEFAULT 'SYNC',
    requires_approval BOOLEAN DEFAULT FALSE,
    sensitivity VARCHAR(20) DEFAULT 'LOW',
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE mcp_skills IS 'MCP 技能表 (Composite Skills) - 存儲複合技能，支持異步、審批、高敏感權限控制';
COMMENT ON COLUMN mcp_skills.name IS '技能唯一名稱';
COMMENT ON COLUMN mcp_skills.description IS '技能語義描述';
COMMENT ON COLUMN mcp_skills.bean_name IS 'Spring Bean 名稱 (或工作流引擎ID)';
COMMENT ON COLUMN mcp_skills.method_name IS '執行方法名稱';
COMMENT ON COLUMN mcp_skills.input_schema IS '參數 JSON Schema';
COMMENT ON COLUMN mcp_skills.output_schema IS '輸出 JSON Schema';
COMMENT ON COLUMN mcp_skills.run_mode IS 'SYNC 或 ASYNC';
COMMENT ON COLUMN mcp_skills.requires_approval IS '是否需要審批';
COMMENT ON COLUMN mcp_skills.sensitivity IS 'LOW, MEDIUM, HIGH';

-- 3. 任務表 (保持不變)
CREATE TABLE IF NOT EXISTS tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    input_args TEXT,
    result TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE tasks IS '任務表 - 用於異步任務或審批流';
COMMENT ON COLUMN tasks.status IS 'PENDING, RUNNING, COMPLETED, REJECTED, PENDING_APPROVAL';
COMMENT ON COLUMN tasks.input_args IS '執行參數';
COMMENT ON COLUMN tasks.result IS '執行結果';

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
