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
 * 调用 seedWorkspaceModels，验证「全种子化 + 只启用 dashscope-default + 精选默认模型」的注册策略：
 * 所有 provider（云端 + 本地）都种子化进新工作区并显示在「添加提供商」目录里，但只有
 * dashscope-default 默认启用并注入平台 key，其余（含 deepseek-default、dashscope-compat-default、
 * 原版/其它云端、本地）默认禁用；dashscope-default 下只启用并默认 deepseek-v3.2(chat) 与
 * text-embedding-v3(向量)，其余 qwen 模型禁用；配额对 dashscope-default(2M)/deepseek-default(3M)
 * 建行；管理员工作区(id=1)豁免配额。
 * 因 surefire 默认不收 *IT，需用 -Dtest=NewWorkspaceRegistrationSeedIT 显式运行。
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:new_ws_reg_seed_it_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.llm.default-provider-keys.dashscope=sk-test-dashscope-platform-key",
        "mateclaw.llm.default-provider-keys.deepseek=sk-test-deepseek-platform-key"
})
class NewWorkspaceRegistrationSeedIT {

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
    @DisplayName("全部云端 provider 都种子化进新工作区（与模板工作区一致）")
    void allCloudProvidersSeededIntoNewWorkspace() {
        List<String> ws2Cloud = jdbcTemplate.queryForList(
                "SELECT provider_id FROM mate_model_provider WHERE workspace_id = ? AND is_local = FALSE ORDER BY provider_id",
                String.class, NEW_WS);
        List<String> ws1Cloud = jdbcTemplate.queryForList(
                "SELECT provider_id FROM mate_model_provider WHERE workspace_id = 1 AND is_local = FALSE ORDER BY provider_id",
                String.class);
        assertEquals(ws1Cloud, ws2Cloud, "新工作区应种子化与模板工作区相同的全部云端 provider");
        assertTrue(ws2Cloud.size() > 2, "云端 provider 应远多于两个默认版，实际: " + ws2Cloud);
        assertTrue(ws2Cloud.contains("openai") && ws2Cloud.contains("dashscope"),
                "原版/其它云端 provider 也应种子化并显示，实际: " + ws2Cloud);
    }

    @Test
    @DisplayName("注册时只有 dashscope-default 一个默认启用")
    void onlyDashscopeDefaultEnabledAtRegistration() {
        List<String> enabled = jdbcTemplate.queryForList(
                "SELECT provider_id FROM mate_model_provider WHERE workspace_id = ? AND enabled = TRUE ORDER BY provider_id",
                String.class, NEW_WS);
        assertEquals(List.of("dashscope-default"), enabled,
                "注册时只应启用 dashscope-default，实际: " + enabled);
    }

    @Test
    @DisplayName("dashscope-default 下只启用 deepseek-v3.2(chat) + text-embedding-v3(向量)")
    void onlyTwoModelsEnabledUnderDashscopeDefault() {
        List<String> enabledModels = jdbcTemplate.queryForList(
                "SELECT model_name FROM mate_model_config " +
                "WHERE workspace_id = ? AND provider = 'dashscope-default' AND enabled = TRUE AND deleted = 0 " +
                "ORDER BY model_name",
                String.class, NEW_WS);
        assertEquals(List.of("deepseek-v3.2", "text-embedding-v3"), enabledModels,
                "dashscope-default 下只应启用这两个模型，实际: " + enabledModels);

        // 其它 qwen 模型应被禁用（它们存在但 enabled=FALSE）
        Integer qwenEnabled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_config " +
                "WHERE workspace_id = ? AND provider = 'dashscope-default' AND model_name LIKE 'qwen%' AND enabled = TRUE AND deleted = 0",
                Integer.class, NEW_WS);
        assertEquals(0, qwenEnabled, "dashscope-default 下的 qwen 模型注册时应被禁用");
    }

    @Test
    @DisplayName("dashscope-compat-default 已种子化但默认禁用")
    void compatDefaultSeededButDisabled() {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_provider WHERE workspace_id = ? AND provider_id = 'dashscope-compat-default'",
                Integer.class, NEW_WS);
        assertEquals(1, cnt, "dashscope-compat-default 应已种子化进新工作区");
        Boolean enabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM mate_model_provider WHERE workspace_id = ? AND provider_id = 'dashscope-compat-default'",
                Boolean.class, NEW_WS);
        assertFalse(Boolean.TRUE.equals(enabled), "dashscope-compat-default 注册时应默认禁用");
    }

    @Test
    @DisplayName("本地 provider 已种子化但默认禁用")
    void localProvidersSeededButDisabled() {
        Integer localCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_provider WHERE workspace_id = ? AND is_local = TRUE",
                Integer.class, NEW_WS);
        assertNotNull(localCount);
        assertTrue(localCount > 0, "本地 provider（Ollama 等）应被种子化");
        Integer enabledLocal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_provider WHERE workspace_id = ? AND is_local = TRUE AND enabled = TRUE",
                Integer.class, NEW_WS);
        assertEquals(0, enabledLocal, "本地 provider 注册时应默认禁用");
    }

    @Test
    @DisplayName("全部模型都复制进新工作区（无 provider 过滤）")
    void allModelsCopiedIntoNewWorkspace() {
        Integer ws2Models = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_config WHERE workspace_id = ? AND deleted = 0", Integer.class, NEW_WS);
        Integer ws1Models = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_config WHERE workspace_id = 1 AND deleted = 0", Integer.class);
        assertEquals(ws1Models, ws2Models, "新工作区应复制模板工作区的全部模型，不做 provider 过滤");
    }

    @Test
    @DisplayName("新工作区默认聊天模型挂在 dashscope-default/deepseek-v3.2 上")
    void defaultChatModelIsDeepseekV32OnDashscopeDefault() {
        List<Map<String, Object>> defaults = jdbcTemplate.queryForList(
                "SELECT provider, model_name FROM mate_model_config " +
                "WHERE workspace_id = ? AND is_default = TRUE AND deleted = 0 " +
                "  AND (model_type IS NULL OR model_type = 'chat')",
                NEW_WS);
        assertEquals(1, defaults.size(), "应有且仅有一个默认聊天模型，实际: " + defaults);
        assertEquals("dashscope-default", defaults.get(0).get("provider"));
        assertEquals("deepseek-v3.2", defaults.get(0).get("model_name"),
                "默认聊天模型应为 deepseek-v3.2（注册整理后从 qwen-plus 改挂）");
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
                "默认 embedding 必须随复制落在 dashscope-default，否则新用户知识库 embedding 不可用");
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
        assertNull(quotaService.getQuota(1L, "dashscope-default"), "管理员工作区应豁免配额");
        assertNotNull(quotaService.getQuota(NEW_WS, "dashscope-default"), "新工作区应有可见配额");
    }
}
