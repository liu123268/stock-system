CREATE TABLE invitation_join_attempt (
    user_id BIGINT UNSIGNED NOT NULL,
    failure_count INT UNSIGNED NOT NULL DEFAULT 0,
    window_started_at DATETIME(3) NOT NULL,
    CONSTRAINT pk_invitation_join_attempt PRIMARY KEY (user_id),
    CONSTRAINT ck_invitation_join_attempt_count CHECK (failure_count BETWEEN 0 AND 5),
    CONSTRAINT fk_invitation_join_attempt_user FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
