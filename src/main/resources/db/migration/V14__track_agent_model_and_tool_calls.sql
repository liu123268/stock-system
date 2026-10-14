alter table agent_run
    add column model_calls int unsigned not null default 0,
    add column tool_calls int unsigned not null default 0,
    add column tool_trace_json json null;
