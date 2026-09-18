ALTER TABLE stock_opinion
    DROP CHECK ck_stock_opinion_type,
    ADD COLUMN withdrawn_at DATETIME(3) NULL AFTER created_at,
    ADD COLUMN withdrawn_by_user_id BIGINT UNSIGNED NULL AFTER withdrawn_at,
    ADD CONSTRAINT ck_stock_opinion_type CHECK (type IN ('INITIAL', 'UPDATE', 'REVIEW')),
    ADD CONSTRAINT ck_stock_opinion_withdrawn CHECK (
        (withdrawn_at IS NULL AND withdrawn_by_user_id IS NULL)
        OR (withdrawn_at IS NOT NULL AND withdrawn_by_user_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_stock_opinion_withdrawn_by FOREIGN KEY (withdrawn_by_user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;
