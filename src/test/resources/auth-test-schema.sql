CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    full_name VARCHAR(150),
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    `role` VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE activity_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    activity_type VARCHAR(50) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    file_name VARCHAR(255),
    activity_status VARCHAR(24) NOT NULL DEFAULT 'RECORDED',
    activity_time TIMESTAMP NOT NULL,
    CONSTRAINT fk_auth_activity_logs_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE account_recovery_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_recovery_token_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    file_sha256 VARCHAR(64),
    placement_strategy VARCHAR(20),
    content_type VARCHAR(255),
    total_chunks INT NOT NULL,
    upload_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE cloud_providers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_name VARCHAR(50) NOT NULL
);

CREATE TABLE chunk_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chunk_hash VARCHAR(64) NOT NULL UNIQUE,
    chunk_size BIGINT NOT NULL,
    similarity_signature VARCHAR(4096),
    reference_count BIGINT NOT NULL DEFAULT 1
);

CREATE TABLE file_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    chunk_number INT NOT NULL,
    chunk_hash VARCHAR(64) NOT NULL,
    chunk_size BIGINT NOT NULL,
    cloud_provider_id BIGINT NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    encryption_status VARCHAR(20) NOT NULL,
    encryption_iv VARCHAR(24),
    encryption_key_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE placement_experiments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ran_by_user_id BIGINT NOT NULL,
    random_seed BIGINT NOT NULL,
    input_chunk_count INT NOT NULL,
    ran_at TIMESTAMP NOT NULL
);

CREATE TABLE placement_strategy_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id BIGINT NOT NULL,
    strategy VARCHAR(32) NOT NULL,
    cloud1_chunk_count BIGINT NOT NULL,
    cloud2_chunk_count BIGINT NOT NULL,
    cloud3_chunk_count BIGINT NOT NULL,
    cloud1_storage_bytes BIGINT NOT NULL,
    cloud2_storage_bytes BIGINT NOT NULL,
    cloud3_storage_bytes BIGINT NOT NULL,
    pairwise_jaccard_json VARCHAR(8192) NOT NULL
);

CREATE TABLE provider_leakage_analyses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_by_user_id BIGINT NOT NULL,
    overall_jaccard DOUBLE,
    high_similarity_threshold DOUBLE NOT NULL,
    analyzed_pair_count INT NOT NULL,
    high_similarity_pair_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_provider_leakage_created_by FOREIGN KEY (created_by_user_id) REFERENCES users(id)
);

CREATE TABLE provider_leakage_pairs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    analysis_id BIGINT NOT NULL,
    provider_a_id BIGINT NOT NULL,
    provider_b_id BIGINT NOT NULL,
    fingerprint_count_a BIGINT NOT NULL,
    fingerprint_count_b BIGINT NOT NULL,
    shared_fingerprint_count BIGINT NOT NULL,
    union_fingerprint_count BIGINT NOT NULL,
    jaccard_similarity DOUBLE,
    shared_fingerprint_percentage DOUBLE,
    data_available BOOLEAN NOT NULL,
    high_similarity BOOLEAN NOT NULL,
    CONSTRAINT uk_provider_leakage_pair UNIQUE (analysis_id, provider_a_id, provider_b_id),
    CONSTRAINT fk_provider_leakage_pair_analysis
        FOREIGN KEY (analysis_id) REFERENCES provider_leakage_analyses(id),
    CONSTRAINT fk_provider_leakage_pair_provider_a FOREIGN KEY (provider_a_id) REFERENCES cloud_providers(id),
    CONSTRAINT fk_provider_leakage_pair_provider_b FOREIGN KEY (provider_b_id) REFERENCES cloud_providers(id)
);
