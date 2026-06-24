-- V146: 数字员工「是否内置」标记（builtin）+ 可见性 / 编辑权限基础（MySQL）。
-- 语义同 H2 版，见 db/migration/h2/V146__agent_builtin.sql。
--
-- builtin = TRUE  → 内置：所有用户可见，仅 admin 可改。
-- builtin = FALSE → 用户私有：仅创建者本人可见、可改。
--
-- INFORMATION_SCHEMA guard：MySQL 不支持 ADD COLUMN IF NOT EXISTS。
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND COLUMN_NAME = 'builtin'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_agent ADD COLUMN builtin TINYINT(1) NOT NULL DEFAULT 1',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- 回填既有行：无主 / admin 创建的保持内置；普通用户自建的降级为非内置。
UPDATE mate_agent
SET builtin = 0
WHERE creator_user_id IS NOT NULL
  AND creator_user_id NOT IN (SELECT id FROM mate_user WHERE role = 'admin');
