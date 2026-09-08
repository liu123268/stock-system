CREATE TABLE stock (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(50) NOT NULL,
    exchange VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_stock PRIMARY KEY (id),
    CONSTRAINT uq_stock_code UNIQUE (code),
    CONSTRAINT ck_stock_code CHECK (CHAR_LENGTH(code) BETWEEN 3 AND 20),
    CONSTRAINT ck_stock_name CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 1 AND 50),
    CONSTRAINT ck_stock_exchange CHECK (exchange IN ('SSE', 'SZSE', 'HKEX')),
    CONSTRAINT ck_stock_currency CHECK (currency IN ('CNY', 'HKD')),
    CONSTRAINT ck_stock_listing_status CHECK (listing_status IN ('LISTED', 'SUSPENDED', 'DELISTED')),
    CONSTRAINT ck_stock_source CHECK (CHAR_LENGTH(source) BETWEEN 1 AND 30)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workspace_stock (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT UNSIGNED NOT NULL,
    stock_id BIGINT UNSIGNED NOT NULL,
    first_added_by_user_id BIGINT UNSIGNED NOT NULL,
    first_added_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_workspace_stock PRIMARY KEY (id),
    CONSTRAINT uq_workspace_stock UNIQUE (workspace_id, stock_id),
    CONSTRAINT fk_workspace_stock_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_workspace_stock_stock FOREIGN KEY (stock_id) REFERENCES stock (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_workspace_stock_first_added_by FOREIGN KEY (first_added_by_user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_workspace_stock_stock (stock_id, workspace_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE research_cycle (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    workspace_stock_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    attention_level VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NORMAL',
    started_by_user_id BIGINT UNSIGNED NOT NULL,
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    start_price DECIMAL(18, 4) NOT NULL,
    quote_time DATETIME(3) NOT NULL,
    quote_source VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    is_simulated BOOLEAN NOT NULL,
    ended_at DATETIME(3) NULL,
    active_workspace_stock_id BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN workspace_stock_id ELSE NULL END
    ) STORED,
    CONSTRAINT pk_research_cycle PRIMARY KEY (id),
    CONSTRAINT uq_research_cycle_active UNIQUE (active_workspace_stock_id),
    CONSTRAINT ck_research_cycle_status CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_research_cycle_attention CHECK (attention_level IN ('NORMAL', 'FOCUS')),
    CONSTRAINT ck_research_cycle_start_price CHECK (start_price > 0),
    CONSTRAINT ck_research_cycle_quote_source CHECK (CHAR_LENGTH(quote_source) BETWEEN 1 AND 30),
    CONSTRAINT ck_research_cycle_simulated CHECK (is_simulated IN (0, 1)),
    CONSTRAINT ck_research_cycle_end_time CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL)
        OR (status = 'ENDED' AND ended_at IS NOT NULL AND ended_at >= started_at)
    ),
    CONSTRAINT fk_research_cycle_workspace_stock FOREIGN KEY (workspace_stock_id) REFERENCES workspace_stock (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_research_cycle_started_by FOREIGN KEY (started_by_user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_research_cycle_history (workspace_stock_id, started_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE stock_opinion (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    research_cycle_id BIGINT UNSIGNED NOT NULL,
    author_user_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stance VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    risk_note VARCHAR(1000) NOT NULL,
    horizon VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_price DECIMAL(18, 4) NULL,
    stop_loss_price DECIMAL(18, 4) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    initial_cycle_id BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN type = 'INITIAL' THEN research_cycle_id ELSE NULL END
    ) STORED,
    CONSTRAINT pk_stock_opinion PRIMARY KEY (id),
    CONSTRAINT uq_stock_opinion_initial UNIQUE (initial_cycle_id),
    CONSTRAINT ck_stock_opinion_type CHECK (type IN ('INITIAL')),
    CONSTRAINT ck_stock_opinion_stance CHECK (stance IN ('BULLISH', 'NEUTRAL', 'CAUTIOUS')),
    CONSTRAINT ck_stock_opinion_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_stock_opinion_risk CHECK (CHAR_LENGTH(TRIM(risk_note)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_stock_opinion_horizon CHECK (horizon IN ('SHORT', 'MEDIUM', 'LONG')),
    CONSTRAINT ck_stock_opinion_target CHECK (target_price IS NULL OR target_price > 0),
    CONSTRAINT ck_stock_opinion_stop_loss CHECK (stop_loss_price IS NULL OR stop_loss_price > 0),
    CONSTRAINT fk_stock_opinion_cycle FOREIGN KEY (research_cycle_id) REFERENCES research_cycle (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_opinion_author FOREIGN KEY (author_user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_stock_opinion_cycle_created (research_cycle_id, created_at, id),
    INDEX idx_stock_opinion_author_created (author_user_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
