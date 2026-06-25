-- V150: 删除各工作区自动播种的「默认助手」(MySQL)—— 语义同 H2 版,见
-- db/migration/h2/V150__drop_seeded_default_agents.sql。
--
-- 统一使用全局内置「通用助手」(workspace 1) + 数字员工;seedDefaultAgent 已停用。
-- 仅删系统播种的(creator_user_id IS NULL),用户自建同名 agent 不动。
-- 子查询读 mate_agent、删 mate_workspace_file —— 两表不同,无 error 1093。
DELETE FROM mate_workspace_file
WHERE agent_id IN (
    SELECT id FROM (
        SELECT id FROM mate_agent WHERE name = '默认助手' AND creator_user_id IS NULL
    ) t
);

DELETE FROM mate_agent
WHERE name = '默认助手' AND creator_user_id IS NULL;
