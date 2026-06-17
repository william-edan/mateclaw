-- P2 隔离纵深：给仅经 FK 间接隔离的表补 workspace_id 列并从关联表回填。
-- 为 Part 5 的 TenantLineInnerInterceptor 逐表灰度铺路。
-- 本阶段列为冗余、nullable，查询不变；NOT NULL 留到 Part 5 逐表确认后。

-- ===== Wiki 子表：kb_id -> mate_wiki_knowledge_base.workspace_id =====
ALTER TABLE mate_wiki_chunk ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_wiki_chunk c JOIN mate_wiki_knowledge_base kb ON kb.id = c.kb_id
    SET c.workspace_id = kb.workspace_id;

ALTER TABLE mate_wiki_page ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_wiki_page c JOIN mate_wiki_knowledge_base kb ON kb.id = c.kb_id
    SET c.workspace_id = kb.workspace_id;

ALTER TABLE mate_wiki_relation ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_wiki_relation c JOIN mate_wiki_knowledge_base kb ON kb.id = c.kb_id
    SET c.workspace_id = kb.workspace_id;

ALTER TABLE mate_wiki_raw_material ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_wiki_raw_material c JOIN mate_wiki_knowledge_base kb ON kb.id = c.kb_id
    SET c.workspace_id = kb.workspace_id;

-- ===== Memory：agent_id -> mate_agent.workspace_id =====
ALTER TABLE mate_fact ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_fact c JOIN mate_agent a ON a.id = c.agent_id
    SET c.workspace_id = a.workspace_id;

ALTER TABLE mate_memory_recall ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_memory_recall c JOIN mate_agent a ON a.id = c.agent_id
    SET c.workspace_id = a.workspace_id;

ALTER TABLE mate_dream_report ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_dream_report c JOIN mate_agent a ON a.id = c.agent_id
    SET c.workspace_id = a.workspace_id;

-- ===== Tool approval：conversation_id(String) -> mate_conversation.workspace_id =====
ALTER TABLE mate_tool_approval ADD COLUMN workspace_id BIGINT NULL;
UPDATE mate_tool_approval c JOIN mate_conversation cv ON cv.conversation_id = c.conversation_id
    SET c.workspace_id = cv.workspace_id;
