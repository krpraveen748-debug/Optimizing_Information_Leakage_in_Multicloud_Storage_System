CREATE TABLE IF NOT EXISTS stored_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(150),
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS file_chunks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  cloud_name VARCHAR(30) NOT NULL,
  relative_path VARCHAR(500) NOT NULL,
  chunk_size INT NOT NULL,
  CONSTRAINT fk_chunk_file FOREIGN KEY (file_id) REFERENCES stored_files(id),
  INDEX idx_chunk_hash (sha256)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS leakage_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  strategy VARCHAR(40) NOT NULL,
  estimated_leakage DOUBLE NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_leak_file FOREIGN KEY (file_id) REFERENCES stored_files(id)
) ENGINE=InnoDB;
