-- P2 隔离纵深(防御纵深尾批)：再给 4 张仅 FK 间接隔离的表补 workspace_id 列并回填。
-- 4 张全部单跳，父表 workspace_id 均为 NOT NULL 原生列，回填可靠。
-- 本阶段列为冗余、nullable，查询不变；NOT NULL 留到 Part 5 逐表灰度确认后。

ALTER TABLE mate_skill_file ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_skill_file c JOIN mate_skill s ON s.id = c.skill_id
    SET c.workspace_id = s.workspace_id;

ALTER TABLE mate_workflow_revision ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_workflow_revision c JOIN mate_workflow w ON w.id = c.workflow_id
    SET c.workspace_id = w.workspace_id;

-- run_step 归属 mate_workflow_run（不是 agent_run）
ALTER TABLE mate_workflow_run_step ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_workflow_run_step c JOIN mate_workflow_run r ON r.id = c.run_id
    SET c.workspace_id = r.workspace_id;

-- agent_pause 归属 mate_agent_run（os/run kernel，不是 workflow_run）
ALTER TABLE mate_agent_pause ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_agent_pause c JOIN mate_agent_run r ON r.id = c.run_id
    SET c.workspace_id = r.workspace_id;
