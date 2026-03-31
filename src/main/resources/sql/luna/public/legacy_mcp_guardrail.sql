-- Legacy MCP guardrail:
-- 1) block all writes to mcp_tools / mcp_skills at DB layer
-- 2) keep audit log for denied writes
-- 3) store read traffic metrics by app version (filled by application aspect)

create table if not exists legacy_mcp_write_audit (
    id bigserial primary key,
    occurred_at timestamp not null default current_timestamp,
    legacy_table varchar(64) not null,
    operation varchar(16) not null,
    app_version varchar(64) not null default 'unknown',
    db_user_name varchar(128) not null default current_user,
    client_addr inet,
    payload_json jsonb
);

create table if not exists legacy_mcp_read_metric (
    id bigserial primary key,
    app_version varchar(64) not null,
    legacy_table varchar(64) not null,
    read_count bigint not null default 0,
    first_seen_at timestamp not null default current_timestamp,
    last_seen_at timestamp not null default current_timestamp,
    constraint uk_legacy_mcp_read_metric unique (app_version, legacy_table)
);

create or replace function fn_block_legacy_mcp_write()
returns trigger
language plpgsql
as $$
declare
    v_version text;
    v_payload jsonb;
begin
    v_version := coalesce(nullif(current_setting('luna.release_version', true), ''), 'unknown');
    if tg_op = 'DELETE' then
        v_payload := to_jsonb(old);
    else
        v_payload := to_jsonb(new);
    end if;

    insert into legacy_mcp_write_audit(legacy_table, operation, app_version, client_addr, payload_json)
    values (tg_table_name, tg_op, v_version, inet_client_addr(), v_payload);

    raise exception 'legacy table % is readonly: % blocked', tg_table_name, tg_op
        using errcode = 'P0001';
end;
$$;

drop trigger if exists trg_block_mcp_tools_write on mcp_tools;
create trigger trg_block_mcp_tools_write
before insert or update or delete on mcp_tools
for each row execute function fn_block_legacy_mcp_write();

drop trigger if exists trg_block_mcp_skills_write on mcp_skills;
create trigger trg_block_mcp_skills_write
before insert or update or delete on mcp_skills
for each row execute function fn_block_legacy_mcp_write();

create or replace view legacy_mcp_read_metric_latest as
select app_version,
       legacy_table,
       read_count,
       first_seen_at,
       last_seen_at
from legacy_mcp_read_metric;

