-- P2 隔离纵深(防御纵深尾批)：再给 4 张仅 FK 间接隔离的表补 workspace_id 列并回填。
-- 4 张全部单跳，父表 workspace_id 均为 NOT NULL 原生列，回填可靠。
-- 本阶段列为冗余、nullable，查询不变；NOT NULL 留到 Part 5 逐表灰度确认后。

ALTER TABLE mate_skill_file ADD COLUMN workspace_id BIGINT;
UPDATE mate_skill_file c SET workspace_id =
    (SELECT s.workspace_id FROM mate_skill s WHERE s.id = c.skill_id);

ALTER TABLE mate_workflow_revision ADD COLUMN workspace_id BIGINT;
UPDATE mate_workflow_revision c SET workspace_id =
    (SELECT w.workspace_id FROM mate_workflow w WHERE w.id = c.workflow_id);

-- run_step 归属 mate_workflow_run（不是 agent_run）
ALTER TABLE mate_workflow_run_step ADD COLUMN workspace_id BIGINT;
UPDATE mate_workflow_run_step c SET workspace_id =
    (SELECT r.workspace_id FROM mate_workflow_run r WHERE r.id = c.run_id);

-- agent_pause 归属 mate_agent_run（os/run kernel，不是 workflow_run）
ALTER TABLE mate_agent_pause ADD COLUMN workspace_id BIGINT;
UPDATE mate_agent_pause c SET workspace_id =
    (SELECT r.workspace_id FROM mate_agent_run r WHERE r.id = c.run_id);
