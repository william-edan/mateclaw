package vip.mate.workspace.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check of the V143 phase-2 workspace_id backfill migration: run the
 * REAL h2 migration file against a minimal schema with sample rows and verify it
 * (a) adds workspace_id to every target table and (b) backfills correctly for all
 * three relation patterns (kb_id, agent_id, conversation_id). Pure embedded H2 —
 * no Spring context, no Flyway.
 */
class WorkspaceIdBackfillMigrationTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("wsid_backfill_" + UUID.randomUUID())
                .build();
        jdbc = new JdbcTemplate(db);

        // Parents carry workspace_id.
        jdbc.execute("CREATE TABLE mate_wiki_knowledge_base (id BIGINT PRIMARY KEY, workspace_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_agent (id BIGINT PRIMARY KEY, workspace_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_conversation (id BIGINT PRIMARY KEY, conversation_id VARCHAR(128), workspace_id BIGINT)");
        // Wiki children keyed by kb_id.
        jdbc.execute("CREATE TABLE mate_wiki_chunk (id BIGINT PRIMARY KEY, kb_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_wiki_page (id BIGINT PRIMARY KEY, kb_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_wiki_relation (id BIGINT PRIMARY KEY, kb_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_wiki_raw_material (id BIGINT PRIMARY KEY, kb_id BIGINT)");
        // Memory children keyed by agent_id.
        jdbc.execute("CREATE TABLE mate_fact (id BIGINT PRIMARY KEY, agent_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_memory_recall (id BIGINT PRIMARY KEY, agent_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_dream_report (id BIGINT PRIMARY KEY, agent_id BIGINT)");
        // Approval keyed by conversation_id (string business key).
        jdbc.execute("CREATE TABLE mate_tool_approval (id BIGINT PRIMARY KEY, conversation_id VARCHAR(128))");

        // One sample row per backfill pattern.
        jdbc.update("INSERT INTO mate_wiki_knowledge_base (id, workspace_id) VALUES (100, 9)");
        jdbc.update("INSERT INTO mate_wiki_chunk (id, kb_id) VALUES (1, 100)");
        jdbc.update("INSERT INTO mate_agent (id, workspace_id) VALUES (200, 8)");
        jdbc.update("INSERT INTO mate_fact (id, agent_id) VALUES (1, 200)");
        jdbc.update("INSERT INTO mate_conversation (id, conversation_id, workspace_id) VALUES (300, 'c-1', 7)");
        jdbc.update("INSERT INTO mate_tool_approval (id, conversation_id) VALUES (1, 'c-1')");

        // Apply the REAL migration file.
        try (Connection conn = db.getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new ClassPathResource("db/migration/h2/V143__backfill_workspace_id_phase2.sql"));
        }
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    @Test
    void backfillsWorkspaceIdFromEachRelationPattern() {
        assertEquals(9L, jdbc.queryForObject("SELECT workspace_id FROM mate_wiki_chunk WHERE id = 1", Long.class));
        assertEquals(8L, jdbc.queryForObject("SELECT workspace_id FROM mate_fact WHERE id = 1", Long.class));
        assertEquals(7L, jdbc.queryForObject("SELECT workspace_id FROM mate_tool_approval WHERE id = 1", Long.class));
    }

    @Test
    void addsWorkspaceIdColumnToEveryTargetTable() {
        for (String table : new String[]{
                "MATE_WIKI_CHUNK", "MATE_WIKI_PAGE", "MATE_WIKI_RELATION", "MATE_WIKI_RAW_MATERIAL",
                "MATE_FACT", "MATE_MEMORY_RECALL", "MATE_DREAM_REPORT", "MATE_TOOL_APPROVAL"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = 'WORKSPACE_ID'",
                    Integer.class, table);
            assertEquals(1, count, table + " must have a workspace_id column after migration");
        }
    }
}
