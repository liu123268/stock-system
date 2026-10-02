-- 只放宽登录账号下限，保留小写字符集、长度上限和原唯一约束。
alter table app_user
    drop check ck_app_user_username,
    add constraint ck_app_user_username check (username regexp '^[a-z0-9_]{2,20}$');
