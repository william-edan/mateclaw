package vip.mate.agent.seed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DigitalEmployeeSeedService}. No Mockito / no DB — the
 * load-bearing {@code parsed .md → AgentEntity} mapping is driven directly via
 * reflection (mapper stays {@code null}), and the shipped resource files are
 * validated by scanning the classpath. Mirrors {@code BuiltinSkillSeedServiceTest}.
 */
class DigitalEmployeeSeedServiceTest {

    /** The 6 categories the Excel sheet assigns; every shipped employee uses one. */
    private static final Set<String> KNOWN_CATEGORIES =
            Set.of("内容生成", "游戏/漫剧", "销售/售前", "品牌设计", "运营优化", "OPC");

    private DigitalEmployeeSeedService service;
    private SkillFrontmatterParser parser;
    private AgentMapper agentMapper;

    @BeforeEach
    void setUp() {
        parser = new SkillFrontmatterParser();
        agentMapper = mock(AgentMapper.class);
        service = new DigitalEmployeeSeedService(agentMapper, parser);
    }

    @Test
    @DisplayName("buildEmployee: frontmatter maps to entity, category → tags, body → systemPrompt")
    void buildsEmployeeFromFrontmatter() throws Exception {
        String md = """
                ---
                name: 抖音策略师
                category: 内容生成
                icon: "🎵"
                description: 在抖音做短视频内容时使用。
                agentType: react
                ---
                # Marketing Douyin Strategist
                You are a Douyin growth specialist.
                """;

        AgentEntity e = invokeBuild(md);

        assertEquals("抖音策略师", e.getName());
        assertEquals("内容生成", e.getTags(), "category lands in tags as a single tag");
        assertEquals("🎵", e.getIcon());
        assertEquals("在抖音做短视频内容时使用。", e.getDescription());
        assertEquals("react", e.getAgentType());
        assertEquals(Boolean.TRUE, e.getEnabled());
        assertEquals(1L, e.getWorkspaceId());
        assertEquals(0, e.getDeleted());
        assertNotNull(e.getSystemPrompt());
        assertTrue(e.getSystemPrompt().contains("Douyin growth specialist"),
                "body becomes the system prompt");
        assertFalse(e.getSystemPrompt().startsWith("---"), "frontmatter is stripped from the prompt");
    }

    @Test
    @DisplayName("buildEmployee: missing icon / agentType fall back to defaults")
    void appliesDefaults() throws Exception {
        String md = """
                ---
                name: 工作室运营
                category: OPC
                ---
                # Studio Operations
                Persona body.
                """;

        AgentEntity e = invokeBuild(md);

        assertEquals("工作室运营", e.getName());
        assertEquals("OPC", e.getTags());
        assertEquals("react", e.getAgentType(), "agentType defaults to react");
        assertNotNull(e.getIcon(), "icon falls back to a default emoji");
        assertFalse(e.getIcon().isBlank());
    }

    @Test
    @DisplayName("Shipped resources: every digital-employees/*.md parses into a valid, categorized employee")
    void shippedResourcesAreValid() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:digital-employees/*.md");

        assertTrue(resources.length >= 36,
                "expected at least 36 seeded employees, found " + resources.length);

        Set<String> seenCategories = new HashSet<>();
        for (Resource r : resources) {
            String filename = r.getFilename();
            assertNotEquals("examples-workflow-landing-page.md", filename,
                    "the example workflow doc must not be seeded as an employee");

            String content = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            AgentEntity e = invokeBuild(content);

            assertNotNull(e.getName(), filename + ": name must not be null");
            assertFalse(e.getName().isBlank(), filename + ": name must not be blank");
            assertNotNull(e.getSystemPrompt(), filename + ": system prompt must not be null");
            assertFalse(e.getSystemPrompt().isBlank(), filename + ": system prompt must not be blank");
            assertTrue(KNOWN_CATEGORIES.contains(e.getTags()),
                    filename + ": category/tags must be one of the 6 known categories, got " + e.getTags());
            assertEquals("react", e.getAgentType(), filename);
            assertEquals(Boolean.TRUE, e.getEnabled(), filename);
            assertEquals(1L, e.getWorkspaceId(), filename);
            seenCategories.add(e.getTags());
        }

        assertEquals(KNOWN_CATEGORIES, seenCategories,
                "all 6 categories should be represented across the shipped employees");
    }

    @Test
    @DisplayName("seedDigitalEmployees: empty workspace → every shipped employee is inserted")
    void seedsAllWhenWorkspaceEmpty() {
        when(agentMapper.selectCount(any())).thenReturn(0L);
        when(agentMapper.insert(any(AgentEntity.class))).thenReturn(1);

        DigitalEmployeeSeedService.SeedStats stats = service.seedDigitalEmployees();

        assertTrue(stats.inserted() >= 36, "expected ≥36 inserts, got " + stats.inserted());
        assertEquals(0, stats.failed(), "no resource should fail to parse");
        verify(agentMapper, atLeast(36)).insert(any(AgentEntity.class));
    }

    @Test
    @DisplayName("seedDigitalEmployees: idempotent — names already present are skipped, nothing inserted")
    void seedIsIdempotentWhenAlreadyPresent() {
        // Every existence check reports the employee already exists.
        when(agentMapper.selectCount(any())).thenReturn(1L);

        DigitalEmployeeSeedService.SeedStats stats = service.seedDigitalEmployees();

        assertEquals(0, stats.inserted(), "nothing should be inserted on a re-seed");
        assertTrue(stats.skipped() >= 36, "all shipped employees should be skipped as already-present");
        verify(agentMapper, never()).insert(any(AgentEntity.class));
    }

    // ==================== reflection helper ====================

    private AgentEntity invokeBuild(String md) throws Exception {
        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse(md);
        Method m = DigitalEmployeeSeedService.class.getDeclaredMethod(
                "buildEmployee", SkillFrontmatterParser.ParsedSkillMd.class);
        m.setAccessible(true);
        return (AgentEntity) m.invoke(service, parsed);
    }
}
