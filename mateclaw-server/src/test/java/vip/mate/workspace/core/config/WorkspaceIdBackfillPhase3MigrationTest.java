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
 * End-to-end check of the V144 phase-3 (defense-in-depth tail) backfill: run the
 * REAL h2 migration against a minimal schema and verify it adds workspace_id to
 * the 4 tables and backfills each single-hop relation (skill_id, workflow_id, and
 * two distinct run_id parents — workflow_run vs agent_run).
 */
class WorkspaceIdBackfillPhase3MigrationTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("wsid_backfill3_" + UUID.randomUUID())
                .build();
        jdbc = new JdbcTemplate(db);

        // Parents (NOT-NULL workspace_id in production).
        jdbc.execute("CREATE TABLE mate_skill (id BIGINT PRIMARY KEY, workspace_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_workflow (id BIGINT PRIMARY KEY, workspace_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_workflow_run (id BIGINT PRIMARY KEY, workspace_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_agent_run (id BIGINT PRIMARY KEY, workspace_id BIGINT)");
        // Children.
        jdbc.execute("CREATE TABLE mate_skill_file (id BIGINT PRIMARY KEY, skill_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_workflow_revision (id BIGINT PRIMARY KEY, workflow_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_workflow_run_step (id BIGINT PRIMARY KEY, run_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_agent_pause (id BIGINT PRIMARY KEY, run_id BIGINT)");

        jdbc.update("INSERT INTO mate_skill (id, workspace_id) VALUES (10, 3)");
        jdbc.update("INSERT INTO mate_skill_file (id, skill_id) VALUES (1, 10)");
        jdbc.update("INSERT INTO mate_workflow (id, workspace_id) VALUES (20, 4)");
        jdbc.update("INSERT INTO mate_workflow_revision (id, workflow_id) VALUES (1, 20)");
        jdbc.update("INSERT INTO mate_workflow_run (id, workspace_id) VALUES (30, 5)");
        jdbc.update("INSERT INTO mate_workflow_run_step (id, run_id) VALUES (1, 30)");
        jdbc.update("INSERT INTO mate_agent_run (id, workspace_id) VALUES (40, 6)");
        jdbc.update("INSERT INTO mate_agent_pause (id, run_id) VALUES (1, 40)");

        try (Connection conn = db.getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new ClassPathResource("db/migration/h2/V144__backfill_workspace_id_phase3.sql"));
        }
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    @Test
    void backfillsWorkspaceIdFromEachParent() {
        assertEquals(3L, jdbc.queryForObject("SELECT workspace_id FROM mate_skill_file WHERE id = 1", Long.class));
        assertEquals(4L, jdbc.queryForObject("SELECT workspace_id FROM mate_workflow_revision WHERE id = 1", Long.class));
        assertEquals(5L, jdbc.queryForObject("SELECT workspace_id FROM mate_workflow_run_step WHERE id = 1", Long.class));
        assertEquals(6L, jdbc.queryForObject("SELECT workspace_id FROM mate_agent_pause WHERE id = 1", Long.class));
    }

    @Test
    void addsWorkspaceIdColumnToEveryTargetTable() {
        for (String table : new String[]{
                "MATE_SKILL_FILE", "MATE_WORKFLOW_REVISION", "MATE_WORKFLOW_RUN_STEP", "MATE_AGENT_PAUSE"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = 'WORKSPACE_ID'",
                    Integer.class, table);
            assertEquals(1, count, table + " must have a workspace_id column after migration");
        }
    }
}
