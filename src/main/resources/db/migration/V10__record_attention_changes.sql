CREATE TABLE research_attention_change (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    research_cycle_id BIGINT UNSIGNED NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    from_level VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_level VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    changed_at DATETIME(3) NOT NULL,
    CONSTRAINT pk_research_attention_change PRIMARY KEY (id),
    CONSTRAINT ck_research_attention_change_levels CHECK (
        from_level IN ('NORMAL', 'FOCUS')
        AND to_level IN ('NORMAL', 'FOCUS')
        AND from_level <> to_level
    ),
    CONSTRAINT fk_research_attention_change_cycle FOREIGN KEY (research_cycle_id)
        REFERENCES research_cycle (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_research_attention_change_actor FOREIGN KEY (actor_user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_research_attention_change_cycle_time (research_cycle_id, changed_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
