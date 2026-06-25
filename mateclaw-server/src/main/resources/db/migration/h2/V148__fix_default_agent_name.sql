-- V148: 修复历史脏数据 —— seedDefaultAgent 早期版本因缺失 i18n key
-- "workspace.default_agent.name"，I18nService.msg() 原样返回该 key，导致默认
-- Agent 的 name 被写成字面量 "workspace.default_agent.name"。
-- 该 key 已在 messages.properties / messages_en.properties 补齐，新建工作区不再
-- 出现此问题；本迁移把既有脏行改回「默认助手」。
--
-- 语法对齐项目既有 H2 迁移：UPDATE 用表别名、SET 列名不带前缀（见 V143）；
-- H2 允许在子查询里直接自引用被更新的表（见 V102），无需派生表包裹。
-- NOT EXISTS 守卫：避免与同工作区已存在的「默认助手」撞 (workspace_id, name)
-- 唯一索引（V102）。
UPDATE mate_agent a
SET name = '默认助手'
WHERE a.name = 'workspace.default_agent.name'
  AND NOT EXISTS (
    SELECT 1 FROM mate_agent b
    WHERE b.workspace_id = a.workspace_id
      AND b.name = '默认助手'
  );
