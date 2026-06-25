-- V149: 给 mate_workspace_file 增加 workspace_id 维度（MySQL）—— 语义同 H2 版，见
-- db/migration/h2/V149__workspace_file_workspace_id.sql。
--
-- INFORMATION_SCHEMA guard：MySQL 不支持 ADD COLUMN / CREATE INDEX IF NOT EXISTS。
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_workspace_file'
      AND COLUMN_NAME = 'workspace_id'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_workspace_file ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 AFTER agent_id',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- 回填：按 agent 所属工作区赋值；孤儿行兜底工作区 1。
-- 子查询读 mate_agent（与被更新表不同），无 error 1093 之虞。
UPDATE mate_workspace_file f
SET f.workspace_id = COALESCE(
    (SELECT a.workspace_id FROM mate_agent a WHERE a.id = f.agent_id), 1);

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_workspace_file'
      AND INDEX_NAME = 'idx_workspace_file_ws_agent'
);
SET @stmt2 := IF(@idx_exists = 0,
    'CREATE INDEX idx_workspace_file_ws_agent ON mate_workspace_file(workspace_id, agent_id, filename)',
    'SELECT 1');
PREPARE s2 FROM @stmt2; EXECUTE s2; DEALLOCATE PREPARE s2;
