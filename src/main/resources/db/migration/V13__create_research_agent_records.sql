create table agent_budget (
    id tinyint unsigned not null,
    occupied_yuan decimal(18, 8) not null default 0,
    primary key (id),
    constraint ck_agent_budget_singleton check (id = 1),
    constraint ck_agent_budget_occupied check (occupied_yuan >= 0)
) engine = InnoDB default charset = utf8mb4;

create table agent_session (
    id bigint unsigned not null auto_increment,
    user_id bigint unsigned not null,
    workspace_id bigint unsigned not null,
    workspace_stock_id bigint unsigned not null,
    research_cycle_id bigint unsigned not null,
    context_version bigint unsigned not null default 0,
    primary key (id),
    unique key uq_agent_session_object (user_id, workspace_id, workspace_stock_id, research_cycle_id),
    foreign key (user_id) references app_user (id),
    foreign key (workspace_id) references workspace (id),
    foreign key (workspace_stock_id) references workspace_stock (id),
    foreign key (research_cycle_id) references research_cycle (id)
) engine = InnoDB default charset = utf8mb4;

create table agent_run (
    id bigint unsigned not null auto_increment,
    session_id bigint unsigned not null,
    user_id bigint unsigned not null,
    request_id char(36) character set ascii collate ascii_bin not null,
    request_hash char(64) character set ascii collate ascii_bin not null,
    context_version bigint unsigned not null,
    status varchar(20) character set ascii collate ascii_bin not null,
    error_code varchar(80) character set ascii collate ascii_bin null,
    started_at datetime(3) not null default current_timestamp(3),
    finished_at datetime(3) null,
    dispatched boolean not null default false,
    model varchar(100) null,
    prompt_version varchar(80) not null,
    input_tokens bigint unsigned null,
    output_tokens bigint unsigned null,
    reserved_yuan decimal(18, 8) not null,
    cost_upper_yuan decimal(18, 8) null,
    primary key (id),
    unique key uq_agent_run_request (user_id, request_id),
    key idx_agent_run_active (status, user_id, started_at),
    key idx_agent_run_session (session_id, id),
    foreign key (session_id) references agent_session (id),
    foreign key (user_id) references app_user (id),
    constraint ck_agent_run_status check (status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'CLEARED', 'INTERRUPTED')),
    constraint ck_agent_run_money check (reserved_yuan >= 0 and (cost_upper_yuan is null or cost_upper_yuan >= 0))
) engine = InnoDB default charset = utf8mb4;

create table agent_message (
    id bigint unsigned not null auto_increment,
    session_id bigint unsigned not null,
    run_id bigint unsigned not null,
    result_json json not null,
    dependencies_json json not null,
    primary key (id),
    unique key uq_agent_message_run (run_id),
    key idx_agent_message_session (session_id, id),
    foreign key (session_id) references agent_session (id),
    foreign key (run_id) references agent_run (id)
) engine = InnoDB default charset = utf8mb4;
