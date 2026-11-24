CREATE TABLE account_recovery_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_account_recovery_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_account_recovery_token_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_account_recovery_user_purpose_created (user_id, purpose, created_at),
    INDEX idx_account_recovery_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
