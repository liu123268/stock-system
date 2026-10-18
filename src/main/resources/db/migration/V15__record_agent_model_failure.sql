-- 仅保存允许的分类、阶段、HTTP 状态及本地运行关联标识，不保存异常正文。
alter table agent_run add column model_failure_json json null;
