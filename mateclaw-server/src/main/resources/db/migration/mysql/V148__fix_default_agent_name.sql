-- V148: 修复历史脏数据（MySQL）—— 语义同 H2 版，见
-- db/migration/h2/V148__fix_default_agent_name.sql。
--
-- 早期 seedDefaultAgent 因缺失 i18n key "workspace.default_agent.name"，
-- 把默认 Agent 的 name 写成了字面量 "workspace.default_agent.name"。
-- 该 key 已补齐；本迁移把既有脏行改回「默认助手」。
--
-- 与 H2 版的区别：MySQL 不允许在 UPDATE 的子查询里直接引用被更新的表
-- （error 1093），因此子查询用派生表 (SELECT ... FROM mate_agent) b 包裹一层。
-- SET 列名不带前缀；NOT EXISTS 守卫 (workspace_id, name) 唯一索引冲突。
UPDATE mate_agent a
SET name = '默认助手'
WHERE a.name = 'workspace.default_agent.name'
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT workspace_id, name FROM mate_agent) b
    WHERE b.workspace_id = a.workspace_id
      AND b.name = '默认助手'
  );
