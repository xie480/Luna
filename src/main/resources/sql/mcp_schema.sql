-- 啟用 pgvector 擴展 (如果尚未啟用)
CREATE EXTENSION IF NOT EXISTS vector;

-- 刪除舊表 (如果存在)
DROP TABLE IF EXISTS resources;
DROP TABLE IF EXISTS mcp_tools;
DROP TABLE IF EXISTS mcp_skills;
DROP TABLE IF EXISTS tasks;

-- 1. MCP 工具表 (Atomic Tools)
CREATE TABLE IF NOT EXISTS mcp_tools (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    version VARCHAR(20) DEFAULT '1.0.0',
    owner VARCHAR(50),
    bean_name VARCHAR(100) NOT NULL,
    method_name VARCHAR(100) NOT NULL,
    input_schema TEXT,
    output_schema TEXT,
    requires_approval BOOLEAN DEFAULT FALSE,
    sensitivity VARCHAR(50) DEFAULT 'LOW',
    embedding vector(768),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE mcp_tools IS 'MCP 工具表 (Atomic Tools) - 存儲原子工具，通常是無狀態、同步執行的';
COMMENT ON COLUMN mcp_tools.id IS '主鍵 ID (雪花算法)';
COMMENT ON COLUMN mcp_tools.name IS '工具唯一名稱';
COMMENT ON COLUMN mcp_tools.description IS '工具語義描述';
COMMENT ON COLUMN mcp_tools.bean_name IS 'Spring Bean 名稱';
COMMENT ON COLUMN mcp_tools.method_name IS '執行方法名稱';
COMMENT ON COLUMN mcp_tools.input_schema IS '參數 JSON Schema';
COMMENT ON COLUMN mcp_tools.output_schema IS '輸出 JSON Schema';
COMMENT ON COLUMN mcp_tools.requires_approval IS '是否需要審批';
COMMENT ON COLUMN mcp_tools.sensitivity IS 'LOW, MEDIUM, HIGH';
COMMENT ON COLUMN mcp_tools.embedding IS '工具語義向量 (PGVector, 768維)';

-- 2. MCP 技能表 (Composite Skills)
CREATE TABLE IF NOT EXISTS mcp_skills (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    version VARCHAR(20) DEFAULT '1.0.0',
    owner VARCHAR(50),
    bean_name VARCHAR(100) NOT NULL,
    method_name VARCHAR(100) NOT NULL,
    input_schema TEXT,
    output_schema TEXT,
    run_mode VARCHAR(20) DEFAULT 'SYNC',
    tool_ids JSONB DEFAULT '[]'::jsonb,
    thought_chain TEXT,
    embedding vector(768),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE mcp_skills IS 'MCP 技能表 (Composite Skills) - 存儲複合技能，支持異步執行';
COMMENT ON COLUMN mcp_skills.id IS '主鍵 ID (雪花算法)';
COMMENT ON COLUMN mcp_skills.name IS '技能唯一名稱';
COMMENT ON COLUMN mcp_skills.description IS '技能語義描述';
COMMENT ON COLUMN mcp_skills.bean_name IS 'Spring Bean 名稱 (或工作流引擎ID)';
COMMENT ON COLUMN mcp_skills.method_name IS '執行方法名稱';
COMMENT ON COLUMN mcp_skills.input_schema IS '參數 JSON Schema';
COMMENT ON COLUMN mcp_skills.output_schema IS '輸出 JSON Schema';
COMMENT ON COLUMN mcp_skills.run_mode IS 'SYNC 或 ASYNC';
COMMENT ON COLUMN mcp_skills.tool_ids IS 'Skill 可调用 Tool ID 白名单(JSON数组)';
COMMENT ON COLUMN mcp_skills.thought_chain IS 'Skill 的自然语言编排思维链（顺序、依赖、回退策略）';
COMMENT ON COLUMN mcp_skills.embedding IS '技能語義向量 (PGVector, 768維)';

-- 3. 任務表
CREATE TABLE IF NOT EXISTS tasks (
    task_id BIGINT PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    input_args TEXT,
    result TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE tasks IS '任務表 - 用於異步任務或審批流';
COMMENT ON COLUMN tasks.task_id IS '任務 ID (雪花算法)';
COMMENT ON COLUMN tasks.resource_id IS '關聯的資源 ID';
COMMENT ON COLUMN tasks.status IS 'PENDING, RUNNING, COMPLETED, REJECTED, PENDING_APPROVAL';
COMMENT ON COLUMN tasks.input_args IS '執行參數';
COMMENT ON COLUMN tasks.result IS '執行結果';

-- BTree 索引
CREATE INDEX IF NOT EXISTS idx_mcp_tools_name ON mcp_tools(name);
CREATE INDEX IF NOT EXISTS idx_mcp_skills_name ON mcp_skills(name);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at DESC);

-- skill 白名单与编排字段索引
CREATE INDEX IF NOT EXISTS idx_mcp_skills_tool_ids_gin
    ON mcp_skills USING gin (tool_ids);

-- 向量索引
CREATE INDEX IF NOT EXISTS idx_mcp_tools_embedding_ivfflat
    ON mcp_tools USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_mcp_skills_embedding_ivfflat
    ON mcp_skills USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
