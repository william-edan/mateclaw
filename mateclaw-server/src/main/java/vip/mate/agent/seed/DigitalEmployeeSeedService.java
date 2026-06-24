package vip.mate.agent.seed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Digital-employee seed service.
 *
 * <p>Scans {@code classpath*:digital-employees/*.md} on startup and creates one
 * ready-to-use digital employee ({@code mate_agent} row) per file in the default
 * workspace. Each shipped {@code .md} carries a small YAML frontmatter — {@code
 * name} (display name), {@code category} (业务分类), {@code icon}, {@code
 * description}, {@code agentType} — followed by the persona body that becomes the
 * agent's {@code system_prompt}.
 *
 * <p>The category lands in {@code mate_agent.tags} (a single tag, e.g.
 * {@code 内容生成}), mirroring the convention already used by the productized
 * lead-acquisition seed ({@code V138__seed_lead_acquisition_agent.sql}). The
 * employee page renders that tag as a chip and can filter on it.
 *
 * <p><b>Idempotency.</b> Keyed by {@code (workspace_id, name)} — matching the
 * unique index from {@code V102}. A name already present is left untouched, so a
 * user who edits a seeded employee's prompt or capabilities keeps their changes
 * across reboots. {@code mate_agent} is hard-delete (no {@code @TableLogic}), so
 * deleting a seeded employee frees the name and the next startup re-creates a
 * fresh copy — these 36 are treated as a product baseline that is always present.
 *
 * <p><b>Insert path.</b> Rows are written straight through {@link AgentMapper}
 * rather than {@code AgentService.createAgent} so seeding does not emit 36
 * "spawned" lifecycle events on every boot. Timestamps are auto-filled by the
 * MyBatis-Plus {@code MetaObjectHandler}. This mirrors how {@code
 * BuiltinSkillSeedService} seeds skills.
 *
 * <p><b>Order:</b> 120 — after Flyway and after {@code BuiltinSkillSeedService}
 * (110), so the schema and the default workspace already exist.
 */
@Slf4j
@Service
@Order(120)
@RequiredArgsConstructor
public class DigitalEmployeeSeedService implements ApplicationRunner {

    private static final String EMPLOYEE_GLOB = "classpath*:digital-employees/*.md";
    /** Default workspace — every shipped employee is seeded here. */
    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final String DEFAULT_AGENT_TYPE = "react";
    private static final String DEFAULT_ICON = "🧑‍💼"; // 🧑‍💼

    private final AgentMapper agentMapper;
    private final SkillFrontmatterParser frontmatterParser;

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedDigitalEmployees();
        } catch (Exception e) {
            // Table may not exist yet on a brand-new bootstrap; never block startup.
            log.warn("[EmployeeSeed] Seed failed (table may not be ready yet): {}", e.getMessage());
        }
    }

    /** Public so tests and admin endpoints can re-trigger the seed. */
    public SeedStats seedDigitalEmployees() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(EMPLOYEE_GLOB);
        } catch (Exception e) {
            log.warn("[EmployeeSeed] Failed to scan {}: {}", EMPLOYEE_GLOB, e.getMessage());
            return new SeedStats(0, 0, 0);
        }

        int inserted = 0, skipped = 0, failed = 0;
        for (Resource resource : resources) {
            try {
                String content = readContent(resource);
                SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);

                String name = trimToNull(parsed.getName());
                if (name == null) {
                    log.warn("[EmployeeSeed] {}: no `name` in frontmatter — skipped", resource.getFilename());
                    failed++;
                    continue;
                }
                String systemPrompt = trimToNull(parsed.getBody());
                if (systemPrompt == null) {
                    log.warn("[EmployeeSeed] {}: empty body / system prompt — skipped", resource.getFilename());
                    failed++;
                    continue;
                }

                if (existsByName(name)) {
                    skipped++;
                    continue;
                }

                AgentEntity agent = buildEmployee(parsed);
                try {
                    agentMapper.insert(agent);
                    inserted++;
                    log.info("[EmployeeSeed] inserted '{}' (category={})", name, agent.getTags());
                } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                    // Lost a race against the (workspace_id, name) unique index — the
                    // employee now exists, which is exactly the desired end state.
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("[EmployeeSeed] Failed to process {}: {}", resource.getFilename(), e.getMessage());
                failed++;
            }
        }
        log.info("[EmployeeSeed] Digital employees: {} inserted, {} skipped (already present), {} failed",
                inserted, skipped, failed);
        return new SeedStats(inserted, skipped, failed);
    }

    /**
     * Map a parsed employee {@code .md} to a {@link AgentEntity} ready for
     * insert. Frontmatter supplies {@code name} (display), {@code category}
     * (→ {@code tags}), {@code icon}, {@code agentType}; the body becomes the
     * {@code system_prompt}. Defaults fill the gaps. Pure / no DB so it can be
     * unit-tested directly.
     */
    private AgentEntity buildEmployee(SkillFrontmatterParser.ParsedSkillMd parsed) {
        Map<String, Object> fm = parsed.getFrontmatter();
        AgentEntity agent = new AgentEntity();
        agent.setName(trimToNull(parsed.getName()));
        agent.setDescription(trimToNull(parsed.getDescription()));
        agent.setSystemPrompt(trimToNull(parsed.getBody()));
        agent.setAgentType(fmString(fm, "agentType", DEFAULT_AGENT_TYPE));
        agent.setIcon(fmString(fm, "icon", DEFAULT_ICON));
        agent.setTags(fmString(fm, "category", null)); // 业务分类 → single tag
        agent.setEnabled(true);
        agent.setBuiltin(true); // 系统播种 = 内置：所有用户可见、仅 admin 可改
        agent.setWorkspaceId(DEFAULT_WORKSPACE_ID);
        agent.setDeleted(0);
        return agent;
    }

    private boolean existsByName(String name) {
        Long count = agentMapper.selectCount(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, DEFAULT_WORKSPACE_ID)
                .eq(AgentEntity::getName, name));
        return count != null && count > 0;
    }

    private String readContent(Resource resource) throws Exception {
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fmString(Map<String, Object> fm, String key, String fallback) {
        if (fm == null) return fallback;
        Object v = fm.get(key);
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Outcome counters for one seed pass. */
    public record SeedStats(int inserted, int skipped, int failed) {}
}
