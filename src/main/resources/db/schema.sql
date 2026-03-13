-- 1. 用戶畫像/偏好表 (UserPreference)
CREATE TABLE user_preference (
    id BIGSERIAL PRIMARY KEY,
    pref_key VARCHAR(255) NOT NULL,
    pref_value VARCHAR(500),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE user_preference IS '用戶畫像/偏好表，用於存儲用戶的關鍵設定';
COMMENT ON COLUMN user_preference.id IS '主鍵 ID';
COMMENT ON COLUMN user_preference.pref_key IS '偏好鍵 (如: nickname, birthday, job)';
COMMENT ON COLUMN user_preference.pref_value IS '偏好值 (如: Yilena, 10-27, Developer)';
COMMENT ON COLUMN user_preference.description IS '描述/備註 (用於輔助模型理解該設定的上下文)';
COMMENT ON COLUMN user_preference.created_at IS '創建時間';
COMMENT ON COLUMN user_preference.updated_at IS '更新時間';


-- 2. 本地知識庫表 (KnowledgeBase)
CREATE TABLE knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500),
    content TEXT,
    source_type SMALLINT,
    source_path TEXT,
    vector_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE knowledge_base IS '本地知識庫表，存儲文件解析內容或聯網搜索結果，用於 RAG 檢索';
COMMENT ON COLUMN knowledge_base.id IS '主鍵 ID';
COMMENT ON COLUMN knowledge_base.title IS '標題/文件名/網頁標題';
COMMENT ON COLUMN knowledge_base.content IS '原始文本內容 (分片後的內容)';
COMMENT ON COLUMN knowledge_base.source_type IS '來源類型: 0-FILE, 1-WEB_SEARCH, 2-MANUAL_INPUT (枚舉映射為數字)';
COMMENT ON COLUMN knowledge_base.source_path IS '來源標識 (如文件路徑、URL)';
COMMENT ON COLUMN knowledge_base.vector_id IS '向量數據庫中的 ID (用於關聯 Vector DB)';
COMMENT ON COLUMN knowledge_base.created_at IS '創建時間';
COMMENT ON COLUMN knowledge_base.updated_at IS '更新時間';


-- 3. 長期記憶表 (Memory)
CREATE TABLE luna_memory (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255),
    memory_type SMALLINT,
    content TEXT,
    weight INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE luna_memory IS '長期記憶表';
COMMENT ON COLUMN luna_memory.id IS '主鍵 ID';
COMMENT ON COLUMN luna_memory.session_id IS '會話 ID 或日期標識（例如：2023:10:27）';
COMMENT ON COLUMN luna_memory.memory_type IS '記憶類型: 0-FACT, 1-PREFERENCE, 2-SUMMARY, 3-REFLECTION (枚舉映射為數字)';
COMMENT ON COLUMN luna_memory.content IS '記憶內容';
COMMENT ON COLUMN luna_memory.weight IS '權重，用於標識記憶的重要性，默認為 1';
COMMENT ON COLUMN luna_memory.created_at IS '創建時間';
COMMENT ON COLUMN luna_memory.updated_at IS '更新時間';


-- 4. 日程與待辦事項表 (ScheduleTask)
CREATE TABLE schedule_task (
    id BIGSERIAL PRIMARY KEY,
    content TEXT,
    trigger_time TIMESTAMP,
    status SMALLINT,
    task_type SMALLINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE schedule_task IS '日程與待辦事項表，用於 Luna 主動提醒或執行任務';
COMMENT ON COLUMN schedule_task.id IS '主鍵 ID';
COMMENT ON COLUMN schedule_task.content IS '任務內容';
COMMENT ON COLUMN schedule_task.trigger_time IS '觸發時間 (如果是提醒類任務)';
COMMENT ON COLUMN schedule_task.status IS '狀態: 0-待處理, 1-已完成, 2-已取消, 3-已過期 (枚舉映射為數字)';
COMMENT ON COLUMN schedule_task.task_type IS '任務類型: 0-REMINDER, 1-ACTION, 2-TODO (枚舉映射為數字)';
COMMENT ON COLUMN schedule_task.created_at IS '創建時間';
COMMENT ON COLUMN schedule_task.updated_at IS '更新時間';
