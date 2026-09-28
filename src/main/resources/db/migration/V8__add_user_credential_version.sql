ALTER TABLE app_user
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
