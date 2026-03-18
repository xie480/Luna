-- 啟用 pgvector 擴展 (如果尚未啟用)
CREATE EXTENSION IF NOT EXISTS vector;

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
    
    embedding TEXT, -- 新增：用於存儲向量數據
    
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
COMMENT ON COLUMN mcp_tools.embedding IS '工具語義向量';

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
    
    embedding TEXT, -- 新增：用於存儲向量數據
    
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
COMMENT ON COLUMN mcp_skills.embedding IS '技能語義向量';

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

-- 注意：由於現在改為向量搜索，手動 INSERT 的數據將沒有 embedding。
-- 建議在系統啟動後，通過調用 POST /mcp/tools 接口重新註冊這些工具，
-- 系統會自動調用 Python 腳本生成 embedding 並存入數據庫。
