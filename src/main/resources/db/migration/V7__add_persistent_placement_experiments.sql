CREATE TABLE placement_experiments (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ran_by_user_id BIGINT NOT NULL,
  random_seed BIGINT NOT NULL,
  input_chunk_count INT NOT NULL,
  ran_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_placement_experiment_user
    FOREIGN KEY (ran_by_user_id) REFERENCES users(id),
  INDEX idx_placement_experiment_ran_at (ran_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE placement_strategy_results (
  id BIGINT NOT NULL AUTO_INCREMENT,
  experiment_id BIGINT NOT NULL,
  strategy VARCHAR(32) NOT NULL,
  cloud1_chunk_count BIGINT NOT NULL,
  cloud2_chunk_count BIGINT NOT NULL,
  cloud3_chunk_count BIGINT NOT NULL,
  cloud1_storage_bytes BIGINT NOT NULL,
  cloud2_storage_bytes BIGINT NOT NULL,
  cloud3_storage_bytes BIGINT NOT NULL,
  pairwise_jaccard_json JSON NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_placement_strategy_result
    UNIQUE (experiment_id, strategy),
  CONSTRAINT fk_placement_strategy_experiment
    FOREIGN KEY (experiment_id) REFERENCES placement_experiments(id),
  INDEX idx_placement_strategy_experiment (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
