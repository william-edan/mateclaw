-- P2 隔离纵深：给仅经 FK 间接隔离的表补 workspace_id 列并从关联表回填。
-- 为 Part 5 的 TenantLineInnerInterceptor 逐表灰度铺路。
-- 本阶段列为冗余、nullable，查询不变；NOT NULL 留到 Part 5 逐表确认后。

-- ===== Wiki 子表：kb_id -> mate_wiki_knowledge_base.workspace_id =====
ALTER TABLE mate_wiki_chunk ADD COLUMN workspace_id BIGINT;
UPDATE mate_wiki_chunk c SET workspace_id =
    (SELECT kb.workspace_id FROM mate_wiki_knowledge_base kb WHERE kb.id = c.kb_id);

ALTER TABLE mate_wiki_page ADD COLUMN workspace_id BIGINT;
UPDATE mate_wiki_page c SET workspace_id =
    (SELECT kb.workspace_id FROM mate_wiki_knowledge_base kb WHERE kb.id = c.kb_id);

ALTER TABLE mate_wiki_relation ADD COLUMN workspace_id BIGINT;
UPDATE mate_wiki_relation c SET workspace_id =
    (SELECT kb.workspace_id FROM mate_wiki_knowledge_base kb WHERE kb.id = c.kb_id);

ALTER TABLE mate_wiki_raw_material ADD COLUMN workspace_id BIGINT;
UPDATE mate_wiki_raw_material c SET workspace_id =
    (SELECT kb.workspace_id FROM mate_wiki_knowledge_base kb WHERE kb.id = c.kb_id);

-- ===== Memory：agent_id -> mate_agent.workspace_id =====
ALTER TABLE mate_fact ADD COLUMN workspace_id BIGINT;
UPDATE mate_fact c SET workspace_id =
    (SELECT a.workspace_id FROM mate_agent a WHERE a.id = c.agent_id);

ALTER TABLE mate_memory_recall ADD COLUMN workspace_id BIGINT;
UPDATE mate_memory_recall c SET workspace_id =
    (SELECT a.workspace_id FROM mate_agent a WHERE a.id = c.agent_id);

ALTER TABLE mate_dream_report ADD COLUMN workspace_id BIGINT;
UPDATE mate_dream_report c SET workspace_id =
    (SELECT a.workspace_id FROM mate_agent a WHERE a.id = c.agent_id);

-- ===== Tool approval：conversation_id(String) -> mate_conversation.workspace_id =====
ALTER TABLE mate_tool_approval ADD COLUMN workspace_id BIGINT;
UPDATE mate_tool_approval c SET workspace_id =
    (SELECT cv.workspace_id FROM mate_conversation cv WHERE cv.conversation_id = c.conversation_id);
