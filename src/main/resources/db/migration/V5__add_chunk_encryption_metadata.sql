ALTER TABLE file_chunks
  ADD COLUMN encryption_iv VARCHAR(24) NULL;

ALTER TABLE file_chunks
  ADD COLUMN encryption_key_id VARCHAR(64) NULL;
