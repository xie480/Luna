-- 日志表
CREATE TABLE IF NOT EXISTS luna_log (
    id BIGSERIAL PRIMARY KEY,
    log_type VARCHAR(50) NOT NULL,
    module VARCHAR(100),
    action VARCHAR(100),
    content TEXT,
    request_data JSONB,
    response_data JSONB,
    error_message TEXT,
    error_stack TEXT,
    cost_time BIGINT,
    operator_id VARCHAR(64),
    trace_id VARCHAR(128),
    create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_luna_log_type ON luna_log(log_type);
CREATE INDEX idx_luna_log_module ON luna_log(module);
CREATE INDEX idx_luna_log_create_at ON luna_log(create_at);
