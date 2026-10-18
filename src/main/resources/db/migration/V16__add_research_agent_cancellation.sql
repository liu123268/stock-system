-- 取消请求先持久化；正在发送的模型调用结束后仍须结算用量。
alter table agent_run
    add column cancel_requested boolean not null default false,
    drop check ck_agent_run_status,
    add constraint ck_agent_run_status check (status in
        ('RUNNING', 'SUCCEEDED', 'FAILED', 'CLEARED', 'INTERRUPTED', 'CANCELLED'));
