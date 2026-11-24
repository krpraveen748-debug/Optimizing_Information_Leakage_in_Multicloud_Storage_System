ALTER TABLE files
    ADD COLUMN placement_strategy VARCHAR(20) NULL;

ALTER TABLE activity_logs
    ADD COLUMN file_name VARCHAR(255) NULL,
    ADD COLUMN activity_status VARCHAR(24) NOT NULL DEFAULT 'RECORDED';
