create table plan_report
(
    report_id    bigserial
        primary key,
    plan_id      varchar(64)                         not null
        constraint fk_plan_report_plan
            references plan_instance
            on delete cascade,
    session_id   varchar(128),
    final_status smallint                            not null,
    report_title varchar(255),
    summary      text,
    report_path  text,
    report_url   text,
    open_result  smallint,
    report_html  text,
    created_at   timestamp default CURRENT_TIMESTAMP not null,
    updated_at   timestamp default CURRENT_TIMESTAMP not null
);

comment on table plan_report is '任务报告（HTML）';

comment on column plan_report.final_status is '最终状态编码：0-SUCCESS,1-FAILED,2-PARTIAL,3-CANCELLED';

comment on column plan_report.open_result is '浏览器唤起结果编码：0-SUCCESS,1-FAILED';

comment on column plan_report.report_html is '可选：直接存储HTML内容（较大时建议仅存路径）';

alter table plan_report
    owner to yilena;

create index idx_plan_report_plan_id
    on plan_report (plan_id);

create index idx_plan_report_session_id
    on plan_report (session_id);

create index idx_plan_report_final_status
    on plan_report (final_status);

create index idx_plan_report_created_at
    on plan_report (created_at desc);

create index idx_plan_report_status_time
    on plan_report (final_status asc, created_at desc);

