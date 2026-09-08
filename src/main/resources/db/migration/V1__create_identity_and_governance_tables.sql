CREATE TABLE app_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_username CHECK (username REGEXP '^[a-z0-9_]{4,20}$'),
    CONSTRAINT ck_app_user_nickname CHECK (CHAR_LENGTH(TRIM(nickname)) BETWEEN 1 AND 20),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    INDEX idx_app_user_status_created (status, created_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE super_admin (
    id TINYINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    CONSTRAINT pk_super_admin PRIMARY KEY (id),
    CONSTRAINT uq_super_admin_user UNIQUE (user_id),
    CONSTRAINT ck_super_admin_singleton CHECK (id = 1),
    CONSTRAINT fk_super_admin_user FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE super_admin_operation_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    action VARCHAR(30) NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    before_status VARCHAR(30) NULL,
    after_status VARCHAR(30) NULL,
    result VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_super_admin_operation_log PRIMARY KEY (id),
    CONSTRAINT ck_super_admin_operation_action CHECK (
        action IN ('DISABLE_USER', 'RESTORE_USER', 'ARCHIVE_WORKSPACE', 'RESTORE_WORKSPACE')
    ),
    CONSTRAINT ck_super_admin_operation_result CHECK (result IN ('SUCCESS', 'FAILED')),
    INDEX idx_super_admin_operation_created (created_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
