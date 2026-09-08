CREATE TABLE workspace (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(30) COLLATE utf8mb4_bin NOT NULL,
    created_by_user_id BIGINT UNSIGNED NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    archived_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_workspace PRIMARY KEY (id),
    CONSTRAINT uq_workspace_name UNIQUE (name),
    CONSTRAINT ck_workspace_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 30),
    CONSTRAINT ck_workspace_status CHECK (
        status IN ('ACTIVE', 'ARCHIVED_BY_OWNER', 'ARCHIVED_BY_SUPER_ADMIN')
    ),
    CONSTRAINT ck_workspace_archive_time CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status IN ('ARCHIVED_BY_OWNER', 'ARCHIVED_BY_SUPER_ADMIN') AND archived_at IS NOT NULL)
    ),
    CONSTRAINT fk_workspace_creator FOREIGN KEY (created_by_user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_workspace_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_workspace_status_created (status, created_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workspace_member (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    first_joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_inactive_at DATETIME(3) NULL,
    CONSTRAINT pk_workspace_member PRIMARY KEY (id),
    CONSTRAINT uq_workspace_member UNIQUE (workspace_id, user_id),
    CONSTRAINT ck_workspace_member_status CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    CONSTRAINT ck_workspace_member_join_time CHECK (first_joined_at <= last_joined_at),
    CONSTRAINT ck_workspace_member_inactive_time CHECK (
        (status = 'ACTIVE')
        OR (last_inactive_at IS NOT NULL AND last_inactive_at >= first_joined_at)
    ),
    CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_workspace_member_user_status (user_id, status, workspace_id),
    INDEX idx_workspace_member_workspace_status (workspace_id, status, user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workspace_invitation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT UNSIGNED NOT NULL,
    invite_code CHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_user_id BIGINT UNSIGNED NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    used_count INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    active_workspace_id BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN workspace_id ELSE NULL END
    ) STORED,
    CONSTRAINT pk_workspace_invitation PRIMARY KEY (id),
    CONSTRAINT uq_workspace_invitation_code UNIQUE (invite_code),
    CONSTRAINT uq_workspace_active_invitation UNIQUE (active_workspace_id),
    CONSTRAINT ck_workspace_invitation_code CHECK (invite_code REGEXP '^[A-Z0-9]{6}$'),
    CONSTRAINT ck_workspace_invitation_count CHECK (used_count BETWEEN 0 AND 5),
    CONSTRAINT ck_workspace_invitation_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_workspace_invitation_expiry CHECK (expires_at > created_at),
    CONSTRAINT fk_workspace_invitation_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_workspace_invitation_creator FOREIGN KEY (created_by_user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_workspace_invitation_history (workspace_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
