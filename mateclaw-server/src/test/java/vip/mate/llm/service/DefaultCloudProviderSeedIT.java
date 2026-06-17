package vip.mate.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：验证 DatabaseBootstrapRunner(data-zh.sql) + DefaultProviderKeyBootstrap
 * 在 H2 上完整运行后，三个受管「默认版」provider 已存在、已注入平台 key、已启用，
 * 且默认聊天模型已迁移到 dashscope-default/qwen-plus。
 *
 * 使用独立 H2 数据库，Spring context 仅启动一次。
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:default_provider_seed_it_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        // 使用固定测试 key，确保 hasUsableApiKey 能通过（不含星号/占位词）
        "mateclaw.llm.default-provider-keys.dashscope=sk-test-dashscope-platform-key",
        "mateclaw.llm.default-provider-keys.deepseek=sk-test-deepseek-platform-key"
})
class DefaultCloudProviderSeedIT {

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${mateclaw.llm.default-provider-keys.dashscope}")
    private String configuredDashscopeKey;

    @Value("${mateclaw.llm.default-provider-keys.deepseek}")
    private String configuredDeepseekKey;

    // =========================================================================
    // 断言1: 三个受管 provider 在默认工作区存在且 enabled=true
    // =========================================================================

    @Test
    @DisplayName("dashscope-default 存在于默认工作区且 enabled=true")
    void dashscopeDefaultExists_enabledTrue() {
        ModelProviderEntity p = modelProviderService.getProviderOrNull("dashscope-default");
        assertNotNull(p, "dashscope-default 必须已被 data-zh.sql 种子插入");
        assertTrue(Boolean.TRUE.equals(p.getEnabled()),
                "DefaultProviderKeyBootstrap 必须在注入 key 后将 enabled 置为 true");
    }

    @Test
    @DisplayName("dashscope-compat-default 存在于默认工作区且 enabled=true")
    void dashscopeCompatDefaultExists_enabledTrue() {
        ModelProviderEntity p = modelProviderService.getProviderOrNull("dashscope-compat-default");
        assertNotNull(p, "dashscope-compat-default 必须已被 data-zh.sql 种子插入");
        assertTrue(Boolean.TRUE.equals(p.getEnabled()),
                "DefaultProviderKeyBootstrap 必须在注入 key 后将 enabled 置为 true");
    }

    @Test
    @DisplayName("deepseek-default 存在于默认工作区且 enabled=true")
    void deepseekDefaultExists_enabledTrue() {
        ModelProviderEntity p = modelProviderService.getProviderOrNull("deepseek-default");
        assertNotNull(p, "deepseek-default 必须已被 data-zh.sql 种子插入");
        assertTrue(Boolean.TRUE.equals(p.getEnabled()),
                "DefaultProviderKeyBootstrap 必须在注入 key 后将 enabled 置为 true");
    }

    // =========================================================================
    // 断言2: 各受管 provider 的 api_key 已被注入平台 key（usable + 与配置值一致）
    // =========================================================================

    @Test
    @DisplayName("dashscope-default 的 api_key = 配置的平台 dashscope key")
    void dashscopeDefaultApiKeyMatchesConfig() {
        ModelProviderEntity p = modelProviderService.getProviderOrNull("dashscope-default");
        assertNotNull(p);
        assertTrue(modelProviderService.hasUsableApiKey(p.getApiKey()),
                "dashscope-default.api_key 必须是可用 key（非空/非占位/不含星号）");
        assertEquals(configuredDashscopeKey, p.getApiKey(),
                "dashscope-default.api_key 必须与 mateclaw.llm.default-provider-keys.dashscope 相同");
    }

    @Test
    @DisplayName("dashscope-compat-default 的 api_key = 配置的平台 dashscope key")
    void dashscopeCompatDefaultApiKeyMatchesConfig() {
        ModelProviderEntity p = modelProviderService.getProviderOrNull("dashscope-compat-default");
        assertNotNull(p);
        assertTrue(modelProviderService.hasUsableApiKey(p.getApiKey()),
                "dashscope-compat-default.api_key 必须是可用 key");
        assertEquals(configuredDashscopeKey, p.getApiKey(),
                "dashscope-compat-default.api_key 必须与 mateclaw.llm.default-provider-keys.dashscope 相同");
    }

    @Test
    @DisplayName("deepseek-default 的 api_key = 配置的平台 deepseek key")
    void deepseekDefaultApiKeyMatchesConfig() {
        ModelProviderEntity p = modelProviderService.getProviderOrNull("deepseek-default");
        assertNotNull(p);
        assertTrue(modelProviderService.hasUsableApiKey(p.getApiKey()),
                "deepseek-default.api_key 必须是可用 key");
        assertEquals(configuredDeepseekKey, p.getApiKey(),
                "deepseek-default.api_key 必须与 mateclaw.llm.default-provider-keys.deepseek 相同");
    }

    // =========================================================================
    // 断言3: 默认工作区中唯一一个 is_default=true 的聊天模型属于 dashscope-default/qwen-plus
    // =========================================================================

    @Test
    @DisplayName("默认工作区有且仅有一个 is_default=true 的聊天模型，且属于 dashscope-default/qwen-plus")
    void exactlyOneDefaultChatModel_isOnDashscopeDefault() {
        // 直接查 DB，避免 ModelConfigService.getDefaultModel() 对 provider configured 的过滤干扰计数
        List<ModelConfigEntity> defaults = jdbcTemplate.query(
                "SELECT provider, model_name FROM mate_model_config " +
                "WHERE workspace_id = 1 AND is_default = TRUE AND deleted = 0 " +
                "  AND (model_type IS NULL OR model_type = 'chat')",
                (rs, rowNum) -> {
                    ModelConfigEntity e = new ModelConfigEntity();
                    e.setProvider(rs.getString("provider"));
                    e.setModelName(rs.getString("model_name"));
                    return e;
                });

        assertEquals(1, defaults.size(),
                "默认工作区必须有且仅有 1 个 is_default=true 的聊天模型，实际: " + defaults);

        ModelConfigEntity defaultModel = defaults.get(0);
        assertEquals("dashscope-default", defaultModel.getProvider(),
                "默认聊天模型的 provider 必须是 dashscope-default（而非原版 dashscope）");
        assertEquals("qwen-plus", defaultModel.getModelName(),
                "默认聊天模型的 model_name 必须是 qwen-plus");
    }

    // =========================================================================
    // 断言4: 原版 dashscope provider 的 api_key 仍为空/占位（未被赋予平台 key）
    // =========================================================================

    @Test
    @DisplayName("原版 dashscope provider 的 api_key 仍为空（未被平台 key 覆盖）")
    void plainDashscopeProvider_apiKeyStillEmpty() {
        ModelProviderEntity plain = modelProviderService.getProviderOrNull("dashscope");
        assertNotNull(plain, "原版 dashscope provider 在种子后必须存在");
        assertFalse(modelProviderService.hasUsableApiKey(plain.getApiKey()),
                "原版 dashscope 的 api_key 不应被 DefaultProviderKeyBootstrap 填充（只有受管版才能获得平台 key）");
    }
}
