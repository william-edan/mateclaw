-- V150: 删除各工作区自动播种的「默认助手」。
--
-- 背景:WorkspaceService.seedDefaultAgent 早期给每个新建工作区播种一个默认 agent,
-- 其 builtin=true(全局可见),叠加 V148 把脏 i18n key 名统一改成「默认助手」后,
-- 多个工作区的默认 agent 全部显示出来 = 列表里一堆重复的「默认助手」。
--
-- 改为统一使用全局内置「通用助手」(workspace 1) + 36 个数字员工——它们对所有
-- 工作区可见可用,per-workspace 默认 agent 已无必要(seedDefaultAgent 已在
-- WorkspaceService 停用,新建工作区不再生成)。
--
-- 仅删系统播种的(creator_user_id IS NULL);用户自建的同名 agent 不动。
-- mate_agent 无外键被引用(实测),DELETE 不会被 FK 阻挡。先清这些 agent 的
-- 工作区记忆文件,再删 agent 本体。
DELETE FROM mate_workspace_file
WHERE agent_id IN (
    SELECT id FROM mate_agent WHERE name = '默认助手' AND creator_user_id IS NULL
);

DELETE FROM mate_agent
WHERE name = '默认助手' AND creator_user_id IS NULL;
