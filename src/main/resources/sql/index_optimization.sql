-- 索引优化脚本（PostgreSQL）
-- 说明：
-- 1) 尽量使用 IF NOT EXISTS，避免重复执行报错
-- 2) 向量索引依赖 pgvector 扩展
-- 3) 如在线上大表执行，建议改为 CREATE INDEX CONCURRENTLY（并在独立事务外执行）

CREATE EXTENSION IF NOT EXISTS vector;

------------------------------------------------------------
-- user_preference
------------------------------------------------------------
-- 按偏好键精确查询
CREATE INDEX IF NOT EXISTS idx_user_preference_pref_key
    ON user_preference (pref_key);

-- 按创建时间倒序查看最近偏好
CREATE INDEX IF NOT EXISTS idx_user_preference_created_at
    ON user_preference (created_at DESC);

-- 偏好内容模糊检索（ILIKE/LIKE）
CREATE INDEX IF NOT EXISTS idx_user_preference_pref_value_trgm
    ON user_preference USING gin (pref_value gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_user_preference_description_trgm
    ON user_preference USING gin (description gin_trgm_ops);

------------------------------------------------------------
-- knowledge_base
------------------------------------------------------------
-- 按来源类型过滤
CREATE INDEX IF NOT EXISTS idx_knowledge_base_source_type
    ON knowledge_base (source_type);

-- 按创建时间倒序
CREATE INDEX IF NOT EXISTS idx_knowledge_base_created_at
    ON knowledge_base (created_at DESC);

-- 来源路径过滤
CREATE INDEX IF NOT EXISTS idx_knowledge_base_source_path
    ON knowledge_base (source_path);

-- 向量ID过滤/关联
CREATE INDEX IF NOT EXISTS idx_knowledge_base_vector_id
    ON knowledge_base (vector_id);

-- 标题、内容全文检索
CREATE INDEX IF NOT EXISTS idx_knowledge_base_fts
    ON knowledge_base
    USING gin (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')));

-- 向量检索（embedding 为 vector(768)）
CREATE INDEX IF NOT EXISTS idx_knowledge_base_embedding_ivfflat
    ON knowledge_base
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

------------------------------------------------------------
-- luna_memory
------------------------------------------------------------
-- 按会话ID查询
CREATE INDEX IF NOT EXISTS idx_luna_memory_session_id
    ON luna_memory (session_id);

-- 按记忆类型查询
CREATE INDEX IF NOT EXISTS idx_luna_memory_memory_type
    ON luna_memory (memory_type);

-- 组合索引：会话 + 类型（常见组合过滤）
CREATE INDEX IF NOT EXISTS idx_luna_memory_session_type
    ON luna_memory (session_id, memory_type);

-- 按创建时间倒序
CREATE INDEX IF NOT EXISTS idx_luna_memory_created_at
    ON luna_memory (created_at DESC);

-- 按权重排序/过滤
CREATE INDEX IF NOT EXISTS idx_luna_memory_weight
    ON luna_memory (weight DESC);

------------------------------------------------------------
-- schedule_task
------------------------------------------------------------
-- 按状态过滤
CREATE INDEX IF NOT EXISTS idx_schedule_task_status
    ON schedule_task (status);

-- 按任务类型过滤
CREATE INDEX IF NOT EXISTS idx_schedule_task_task_type
    ON schedule_task (task_type);

-- 待触发任务扫描（时间）
CREATE INDEX IF NOT EXISTS idx_schedule_task_trigger_time
    ON schedule_task (trigger_time);

-- 状态 + 触发时间（调度常用）
CREATE INDEX IF NOT EXISTS idx_schedule_task_status_trigger_time
    ON schedule_task (status, trigger_time);

-- 创建时间倒序
CREATE INDEX IF NOT EXISTS idx_schedule_task_created_at
    ON schedule_task (created_at DESC);

------------------------------------------------------------
-- luna_log
------------------------------------------------------------
-- 你已存在：
-- idx_luna_log_type, idx_luna_log_module, idx_luna_log_create_at

-- action 过滤
CREATE INDEX IF NOT EXISTS idx_luna_log_action
    ON luna_log (action);

-- traceId 链路追踪
CREATE INDEX IF NOT EXISTS idx_luna_log_trace_id
    ON luna_log (trace_id);

-- operator_id 过滤
CREATE INDEX IF NOT EXISTS idx_luna_log_operator_id
    ON luna_log (operator_id);

-- 组合索引：模块 + 动作 + 时间（后台日志检索常用）
CREATE INDEX IF NOT EXISTS idx_luna_log_module_action_create_at
    ON luna_log (module, action, create_at DESC);

-- 错误日志快速筛选（部分索引）
CREATE INDEX IF NOT EXISTS idx_luna_log_error_only
    ON luna_log (create_at DESC)
    WHERE log_type = 'ERROR';

-- 请求/响应 JSONB 检索
CREATE INDEX IF NOT EXISTS idx_luna_log_request_data_gin
    ON luna_log USING gin (request_data);

CREATE INDEX IF NOT EXISTS idx_luna_log_response_data_gin
    ON luna_log USING gin (response_data);

------------------------------------------------------------
-- mcp_tools
------------------------------------------------------------
-- 已有 name 唯一索引（unique）
-- Bean + Method 反射定位
CREATE INDEX IF NOT EXISTS idx_mcp_tools_bean_method
    ON mcp_tools (bean_name, method_name);

-- 审批与敏感度筛选
CREATE INDEX IF NOT EXISTS idx_mcp_tools_requires_approval
    ON mcp_tools (requires_approval);

CREATE INDEX IF NOT EXISTS idx_mcp_tools_sensitivity
    ON mcp_tools (sensitivity);

-- 创建时间倒序
CREATE INDEX IF NOT EXISTS idx_mcp_tools_created_at
    ON mcp_tools (created_at DESC);

-- 工具名/描述模糊检索
CREATE INDEX IF NOT EXISTS idx_mcp_tools_name_trgm
    ON mcp_tools USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_mcp_tools_description_trgm
    ON mcp_tools USING gin (description gin_trgm_ops);

------------------------------------------------------------
-- mcp_skills
------------------------------------------------------------
-- 已有 name 唯一索引（unique）
-- Bean + Method 反射定位
CREATE INDEX IF NOT EXISTS idx_mcp_skills_bean_method
    ON mcp_skills (bean_name, method_name);

-- 执行模式过滤（SYNC/ASYNC）
CREATE INDEX IF NOT EXISTS idx_mcp_skills_run_mode
    ON mcp_skills (run_mode);

-- 创建时间倒序
CREATE INDEX IF NOT EXISTS idx_mcp_skills_created_at
    ON mcp_skills (created_at DESC);

-- 技能名/描述模糊检索
CREATE INDEX IF NOT EXISTS idx_mcp_skills_name_trgm
    ON mcp_skills USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_mcp_skills_description_trgm
    ON mcp_skills USING gin (description gin_trgm_ops);

------------------------------------------------------------
-- tasks
------------------------------------------------------------
-- 状态过滤
CREATE INDEX IF NOT EXISTS idx_tasks_status
    ON tasks (status);

-- 资源维度查询
CREATE INDEX IF NOT EXISTS idx_tasks_resource_id
    ON tasks (resource_id);

-- 创建时间倒序
CREATE INDEX IF NOT EXISTS idx_tasks_created_at
    ON tasks (created_at DESC);

-- 状态 + 创建时间（任务队列拉取常用）
CREATE INDEX IF NOT EXISTS idx_tasks_status_created_at
    ON tasks (status, created_at DESC);

------------------------------------------------------------
-- 可选扩展（用于 trigram 模糊检索）
------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;
