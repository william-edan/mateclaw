-- Scope client-generated conversation ids to a workspace.
DROP INDEX IF EXISTS CONSTRAINT_INDEX_5;
DROP INDEX IF EXISTS UK_CONVERSATION_ID;
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_workspace_id
    ON mate_conversation(workspace_id, conversation_id);
