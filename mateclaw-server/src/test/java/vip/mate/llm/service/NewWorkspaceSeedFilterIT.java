package vip.mate.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：在真实 data-zh.sql 种子 + Flyway(含 V145 模型镜像) 之上，对一个全新工作区(id=2)
 * 调用 seedWorkspaceModels，验证注册种子白名单 + 默认版配额 + 管理员豁免的整链路。
 * 因 surefire 默认不收 *IT，需用 -Dtest=NewWorkspaceSeedFilterIT 显式运行。
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:new_ws_seed_it_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.llm.default-provider-keys.dashscope=sk-test-dashscope-platform-key",
        "mateclaw.llm.default-provider-keys.deepseek=sk-test-deepseek-platform-key"
})
class NewWorkspaceSeedFilterIT {

    private static final long NEW_WS = 2L;

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private ProviderTokenQuotaService quotaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedNewWorkspace() {
        // 幂等：已种子化则直接返回，可被多个 @Test 复用同一份 H2 数据
        modelProviderService.seedWorkspaceModels(NEW_WS);
    }

    @Test
    @DisplayName("新工作区云端 provider 恰为 dashscope-default + deepseek-default")
    void cloudProvidersAreOnlyTheTwoManagedDefaults() {
        List<String> cloud = jdbcTemplate.queryForList(
                "SELECT provider_id FROM mate_model_provider " +
                "WHERE workspace_id = ? AND is_local = FALSE ORDER BY provider_id",
                String.class, NEW_WS);
        assertEquals(List.of("dashscope-default", "deepseek-default"), cloud,
                "新用户云端只应有两个托管默认版 provider，实际: " + cloud);
    }

    @Test
    @DisplayName("新工作区保留了本地 provider")
    void localProvidersArePreserved() {
        Integer localCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_provider WHERE workspace_id = ? AND is_local = TRUE",
                Integer.class, NEW_WS);
        assertNotNull(localCount);
        assertTrue(localCount > 0, "本地 provider（Ollama 等）应被保留");
    }

    @Test
    @DisplayName("新工作区没有指向未种子 provider 的孤儿模型")
    void noOrphanModels() {
        Integer orphan = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_config m WHERE m.workspace_id = ? AND m.deleted = 0 " +
                "AND m.provider NOT IN (SELECT provider_id FROM mate_model_provider WHERE workspace_id = ?)",
                Integer.class, NEW_WS, NEW_WS);
        assertEquals(0, orphan, "不应存在指向未种子化 provider 的模型");

        Integer droppedCloud = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_config WHERE workspace_id = ? AND deleted = 0 " +
                "AND provider IN ('dashscope','dashscope-compat','dashscope-compat-default','deepseek','openai')",
                Integer.class, NEW_WS);
        assertEquals(0, droppedCloud, "被剔除的云端 provider 不应有任何模型残留");
    }

    @Test
    @DisplayName("新工作区默认聊天模型挂在 dashscope-default 上")
    void defaultChatModelIsOnDashscopeDefault() {
        List<Map<String, Object>> defaults = jdbcTemplate.queryForList(
                "SELECT provider, model_name FROM mate_model_config " +
                "WHERE workspace_id = ? AND is_default = TRUE AND deleted = 0 " +
                "  AND (model_type IS NULL OR model_type = 'chat')",
                NEW_WS);
        assertEquals(1, defaults.size(), "应有且仅有一个默认聊天模型，实际: " + defaults);
        assertEquals("dashscope-default", defaults.get(0).get("provider"));
        assertEquals("qwen-plus", defaults.get(0).get("model_name"),
                "默认聊天模型应为 qwen-plus");
    }

    @Test
    @DisplayName("新工作区默认 embedding 模型挂在 dashscope-default 上")
    void defaultEmbeddingModelIsOnDashscopeDefault() {
        List<Map<String, Object>> defaults = jdbcTemplate.queryForList(
                "SELECT provider, model_name FROM mate_model_config " +
                "WHERE workspace_id = ? AND is_default = TRUE AND deleted = 0 AND model_type = 'embedding'",
                NEW_WS);
        assertEquals(1, defaults.size(), "应有且仅有一个默认 embedding 模型，实际: " + defaults);
        assertEquals("dashscope-default", defaults.get(0).get("provider"),
                "默认 embedding 必须随白名单复制到 dashscope-default，否则新用户知识库 embedding 不可用");
    }

    @Test
    @DisplayName("新工作区配额行：dashscope-default=200万, deepseek-default=300万")
    void quotaRowsSeededWithRequestedLimits() {
        Long ds = jdbcTemplate.queryForObject(
                "SELECT limit_tokens FROM mate_provider_token_quota WHERE workspace_id = ? AND provider_id = 'dashscope-default'",
                Long.class, NEW_WS);
        Long de = jdbcTemplate.queryForObject(
                "SELECT limit_tokens FROM mate_provider_token_quota WHERE workspace_id = ? AND provider_id = 'deepseek-default'",
                Long.class, NEW_WS);
        assertEquals(2_000_000L, ds);
        assertEquals(3_000_000L, de);
    }

    @Test
    @DisplayName("管理员工作区(id=1)豁免配额，新工作区受限")
    void adminWorkspaceExemptNewWorkspaceLimited() {
        assertNull(quotaService.getQuota(1L, "dashscope-default"),
                "管理员工作区应豁免配额");
        assertNotNull(quotaService.getQuota(NEW_WS, "dashscope-default"),
                "新工作区应有可见配额");
    }
}
