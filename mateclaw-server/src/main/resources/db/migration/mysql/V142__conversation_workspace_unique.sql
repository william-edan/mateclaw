-- Scope client-generated conversation ids to a workspace. A global UNIQUE on
-- conversation_id makes every tenant share one id namespace and can route a
-- new workspace's "default" / generated id into another workspace's row.

SET @idx := (
    SELECT INDEX_NAME
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_conversation'
      AND NON_UNIQUE = 0
    GROUP BY INDEX_NAME
    HAVING SUM(CASE WHEN COLUMN_NAME = 'conversation_id' THEN 1 ELSE 0 END) = 1
       AND SUM(CASE WHEN COLUMN_NAME <> 'conversation_id' THEN 1 ELSE 0 END) = 0
    LIMIT 1
);
SET @s := IF(@idx IS NULL, 'SELECT 1', CONCAT('ALTER TABLE mate_conversation DROP INDEX ', @idx));
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_conversation'
      AND INDEX_NAME = 'uk_conversation_workspace_id'
);
SET @s := IF(@idx_exists = 0,
    'CREATE UNIQUE INDEX uk_conversation_workspace_id ON mate_conversation(workspace_id, conversation_id)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
