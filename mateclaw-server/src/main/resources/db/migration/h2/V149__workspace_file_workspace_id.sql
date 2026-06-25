-- V149: 给 mate_workspace_file 增加 workspace_id 维度，实现内置数字员工的记忆
-- 按工作区隔离。
--
-- 背景：内置(builtin)Agent 为全局资源，可被任意工作区的用户发起对话。其记忆
-- (MEMORY.md / PROFILE.md / 每日笔记)此前仅按 agent_id 单维存储，导致 A 工作区
-- 用户对话提炼的记忆会注入到 B 工作区用户与同一内置员工的对话——跨租户串台。
-- 加 workspace_id 后，WorkspaceFileService 按 (agent_id, workspace_id) 隔离：
--   * 非内置 Agent：workspace_id = 该 Agent 自身工作区（行为不变，无回归）；
--   * 内置 Agent：workspace_id = 调用方运行工作区（按工作区各存各的）。
--
-- 回填：历史行按其 agent 所属工作区赋值；孤儿行(agent 已删)兜底为工作区 1。
ALTER TABLE mate_workspace_file ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;

UPDATE mate_workspace_file f
SET workspace_id = COALESCE(
    (SELECT a.workspace_id FROM mate_agent a WHERE a.id = f.agent_id), 1);

CREATE INDEX IF NOT EXISTS idx_workspace_file_ws_agent
    ON mate_workspace_file(workspace_id, agent_id, filename);
