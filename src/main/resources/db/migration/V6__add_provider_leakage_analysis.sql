CREATE TABLE provider_leakage_analyses (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_by_user_id BIGINT NOT NULL,
  overall_jaccard DOUBLE NULL,
  high_similarity_threshold DOUBLE NOT NULL,
  analyzed_pair_count INT NOT NULL,
  high_similarity_pair_count INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_provider_leakage_created_by
    FOREIGN KEY (created_by_user_id) REFERENCES users(id),
  INDEX idx_provider_leakage_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE provider_leakage_pairs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  analysis_id BIGINT NOT NULL,
  provider_a_id BIGINT NOT NULL,
  provider_b_id BIGINT NOT NULL,
  fingerprint_count_a BIGINT NOT NULL,
  fingerprint_count_b BIGINT NOT NULL,
  shared_fingerprint_count BIGINT NOT NULL,
  union_fingerprint_count BIGINT NOT NULL,
  jaccard_similarity DOUBLE NULL,
  shared_fingerprint_percentage DOUBLE NULL,
  data_available BOOLEAN NOT NULL,
  high_similarity BOOLEAN NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_provider_leakage_pair
    UNIQUE (analysis_id, provider_a_id, provider_b_id),
  CONSTRAINT fk_provider_leakage_pair_analysis
    FOREIGN KEY (analysis_id) REFERENCES provider_leakage_analyses(id),
  CONSTRAINT fk_provider_leakage_pair_provider_a
    FOREIGN KEY (provider_a_id) REFERENCES cloud_providers(id),
  CONSTRAINT fk_provider_leakage_pair_provider_b
    FOREIGN KEY (provider_b_id) REFERENCES cloud_providers(id),
  CONSTRAINT chk_provider_leakage_distinct_providers
    CHECK (provider_a_id <> provider_b_id),
  INDEX idx_provider_leakage_pair_analysis (analysis_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
