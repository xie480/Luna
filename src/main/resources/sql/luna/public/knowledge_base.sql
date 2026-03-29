create table knowledge_base
(
    id          bigserial
        primary key,
    title       varchar(500),
    content     text,
    source_type smallint,
    source_path text,
    vector_id   varchar(255),
    created_at  timestamp default CURRENT_TIMESTAMP,
    updated_at  timestamp default CURRENT_TIMESTAMP,
    embedding   vector(768)
);

comment on table knowledge_base is '本地知識庫表，存儲文件解析內容或聯網搜索結果，用於 RAG 檢索';

comment on column knowledge_base.id is '主鍵 ID';

comment on column knowledge_base.title is '標題/文件名/網頁標題';

comment on column knowledge_base.content is '原始文本內容 (分片後的內容)';

comment on column knowledge_base.source_type is '來源類型: 0-FILE, 1-WEB_SEARCH, 2-MANUAL_INPUT ';

comment on column knowledge_base.source_path is '來源標識 (如文件路徑、URL)';

comment on column knowledge_base.vector_id is '向量數據庫中的 ID (用於關聯 Vector DB)';

comment on column knowledge_base.created_at is '創建時間';

comment on column knowledge_base.updated_at is '更新時間';

alter table knowledge_base
    owner to yilena;

create index idx_knowledge_base_source_type
    on knowledge_base (source_type);

create index idx_knowledge_base_created_at
    on knowledge_base (created_at desc);

create index idx_knowledge_base_source_path
    on knowledge_base (source_path);

create index idx_knowledge_base_vector_id
    on knowledge_base (vector_id);

create index idx_knowledge_base_fts
    on knowledge_base using gin (to_tsvector('simple'::regconfig,
                                             (COALESCE(title, ''::character varying)::text || ' '::text) ||
                                             COALESCE(content, ''::text)));

create index idx_knowledge_base_embedding_ivfflat
    on knowledge_base using ivfflat (embedding vector_cosine_ops);

