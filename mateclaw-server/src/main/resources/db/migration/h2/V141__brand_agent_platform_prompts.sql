-- V141: Make built-in agent platform identity explicit.
UPDATE mate_agent
SET system_prompt = REPLACE(system_prompt, 'MateClaw 平台', '化帆AI 平台'),
    update_time = NOW()
WHERE id IN (1000000001, 1000000004)
  AND system_prompt LIKE '%MateClaw 平台%';

UPDATE mate_agent
SET system_prompt = REPLACE(system_prompt, '你是 化帆AI 的通用助手。', '你是运行在 化帆AI 平台上的通用助手。'),
    update_time = NOW()
WHERE id = 1000000001
  AND system_prompt LIKE '你是 化帆AI 的通用助手。%';

UPDATE mate_agent
SET system_prompt = REPLACE(system_prompt, '你是 化帆AI 的获客专家。', '你是运行在 化帆AI 平台上的获客专家 Agent。'),
    update_time = NOW()
WHERE id = 1000000004
  AND system_prompt LIKE '你是 化帆AI 的获客专家。%';

UPDATE mate_agent
SET system_prompt = REPLACE(system_prompt, 'You are 化帆AI''s General Assistant.', 'You are the General Assistant running on the 化帆AI platform.'),
    update_time = NOW()
WHERE id = 1000000001
  AND system_prompt LIKE 'You are 化帆AI''s General Assistant.%';
