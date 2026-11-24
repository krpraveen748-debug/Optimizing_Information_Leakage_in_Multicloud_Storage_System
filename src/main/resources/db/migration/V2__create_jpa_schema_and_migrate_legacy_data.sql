CREATE TABLE IF NOT EXISTS users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL,
  email VARCHAR(255) NOT NULL,
  password VARCHAR(255) NOT NULL,
  `role` VARCHAR(20) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  status VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_users_username UNIQUE (username),
  CONSTRAINT uk_users_email UNIQUE (email),
  INDEX idx_users_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cloud_providers (
  id BIGINT NOT NULL AUTO_INCREMENT,
  provider_name VARCHAR(100) NOT NULL,
  storage_directory VARCHAR(1000) NOT NULL,
  total_chunks BIGINT NOT NULL DEFAULT 0,
  total_storage_used BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_cloud_providers_name UNIQUE (provider_name),
  INDEX idx_cloud_providers_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS files (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NULL,
  original_filename VARCHAR(255) NOT NULL,
  stored_filename VARCHAR(255) NOT NULL,
  file_size BIGINT NOT NULL,
  content_type VARCHAR(150) NULL,
  total_chunks INT NOT NULL DEFAULT 0,
  upload_status VARCHAR(20) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_files_user FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_files_user_created (user_id, created_at),
  INDEX idx_files_upload_status (upload_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chunk_metadata (
  id BIGINT NOT NULL AUTO_INCREMENT,
  chunk_hash CHAR(64) NOT NULL,
  chunk_size INT NOT NULL,
  similarity_signature VARCHAR(4096) NULL,
  reference_count BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_chunk_metadata_hash UNIQUE (chunk_hash),
  INDEX idx_chunk_metadata_size (chunk_size)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS leakage_analysis (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  placement_algorithm VARCHAR(64) NOT NULL,
  estimated_leakage DOUBLE NOT NULL,
  similarity_score DOUBLE NULL,
  analysis_date DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_leakage_analysis_file FOREIGN KEY (file_id) REFERENCES files(id),
  INDEX idx_leakage_file_date (file_id, analysis_date),
  INDEX idx_leakage_analysis_date (analysis_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS activity_logs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  activity_type VARCHAR(50) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  activity_time DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_activity_logs_user FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_activity_time (activity_time),
  INDEX idx_activity_user_time (user_id, activity_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO cloud_providers (provider_name, storage_directory, status)
SELECT 'cloud1', './storage/cloud1', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM cloud_providers WHERE provider_name = 'cloud1');

INSERT INTO cloud_providers (provider_name, storage_directory, status)
SELECT 'cloud2', './storage/cloud2', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM cloud_providers WHERE provider_name = 'cloud2');

INSERT INTO cloud_providers (provider_name, storage_directory, status)
SELECT 'cloud3', './storage/cloud3', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM cloud_providers WHERE provider_name = 'cloud3');

INSERT INTO cloud_providers (provider_name, storage_directory, status)
SELECT DISTINCT c.cloud_name, CONCAT('./storage/', c.cloud_name), 'ACTIVE'
FROM file_chunks c
WHERE NOT EXISTS (
  SELECT 1 FROM cloud_providers p WHERE p.provider_name = c.cloud_name
);

INSERT INTO files (
  id, original_filename, stored_filename, file_size, content_type,
  total_chunks, upload_status, created_at
)
SELECT
  f.id, f.original_name, f.original_name, f.size_bytes, f.content_type,
  (SELECT COUNT(*) FROM file_chunks c WHERE c.file_id = f.id),
  'UPLOADED', f.created_at
FROM stored_files f;

INSERT INTO chunk_metadata (chunk_hash, chunk_size, reference_count, created_at)
SELECT c.sha256, MAX(c.chunk_size), COUNT(*), CURRENT_TIMESTAMP(6)
FROM file_chunks c
GROUP BY c.sha256;

INSERT INTO leakage_analysis (
  file_id, placement_algorithm, estimated_leakage, similarity_score, analysis_date
)
SELECT l.file_id, l.strategy, l.estimated_leakage, NULL, l.created_at
FROM leakage_runs l;

CREATE TABLE file_chunks_v2 (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  chunk_number INT NOT NULL,
  chunk_hash CHAR(64) NOT NULL,
  chunk_size INT NOT NULL,
  cloud_provider_id BIGINT NOT NULL,
  storage_path VARCHAR(500) NOT NULL,
  encryption_status VARCHAR(24) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_file_chunks_file_number UNIQUE (file_id, chunk_number),
  CONSTRAINT fk_file_chunks_file FOREIGN KEY (file_id) REFERENCES files(id),
  CONSTRAINT fk_file_chunks_provider FOREIGN KEY (cloud_provider_id) REFERENCES cloud_providers(id),
  CONSTRAINT fk_file_chunks_metadata FOREIGN KEY (chunk_hash) REFERENCES chunk_metadata(chunk_hash),
  INDEX idx_file_chunks_hash (chunk_hash),
  INDEX idx_file_chunks_provider (cloud_provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO file_chunks_v2 (
  id, file_id, chunk_number, chunk_hash, chunk_size, cloud_provider_id,
  storage_path, encryption_status, created_at
)
SELECT
  c.id, c.file_id, c.chunk_index, c.sha256, c.chunk_size, p.id,
  c.relative_path, 'NOT_ENCRYPTED', CURRENT_TIMESTAMP(6)
FROM file_chunks c
JOIN cloud_providers p ON p.provider_name = c.cloud_name;

RENAME TABLE file_chunks TO legacy_file_chunks_v1, file_chunks_v2 TO file_chunks;

UPDATE cloud_providers p
SET total_chunks = (
      SELECT COUNT(*) FROM file_chunks c WHERE c.cloud_provider_id = p.id
    ),
    total_storage_used = (
      SELECT COALESCE(SUM(c.chunk_size), 0) FROM file_chunks c WHERE c.cloud_provider_id = p.id
    );
