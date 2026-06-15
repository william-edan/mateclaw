-- V140: Rename built-in agent dialog prompts from MateClaw to 化帆AI.
UPDATE mate_agent
SET system_prompt = REPLACE(system_prompt, 'MateClaw 的', '化帆AI 的'),
    update_time = NOW()
WHERE id IN (1000000001, 1000000004)
  AND system_prompt LIKE '%MateClaw 的%';

UPDATE mate_agent
SET system_prompt = REPLACE(system_prompt, 'MateClaw''s', '化帆AI''s'),
    update_time = NOW()
WHERE id = 1000000001
  AND system_prompt LIKE '%MateClaw''s%';
