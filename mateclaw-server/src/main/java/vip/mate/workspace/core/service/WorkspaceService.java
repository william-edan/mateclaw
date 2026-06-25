package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.exception.MateClawException;
import vip.mate.i18n.I18nService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.model.WorkspaceAccessVO;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.model.WorkspaceWithRoleVO;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;
import vip.mate.workspace.core.security.RoleCapabilities;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作区业务服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final ConversationMapper conversationMapper;
    private final WikiKnowledgeBaseService wikiKnowledgeBaseService;
    private final I18nService i18n;
    private final AccountEntitlementService entitlementService;
    private final ModelProviderService modelProviderService;
    private final AgentMapper agentMapper;

    /**
     * Root under which each workspace gets its own isolated file-tool sandbox
     * directory ({root}/{workspaceId}). Follows the existing {@code ~/.huafanai/*}
     * convention (skills/plugins). Overridable via {@code mateclaw.workspace.base-root}.
     */
    @Value("${mateclaw.workspace.base-root:${user.home}/.huafanai/workspaces}")
    private String workspaceBaseRoot;

    /** 默认工作区 slug */
    public static final String DEFAULT_SLUG = "default";

    /** 成员资格缓存：key = "workspaceId:userId"，value = role string（null 表示非成员） */
    private final Cache<String, String> membershipCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1000)
            .build();

    // ==================== 工作区 CRUD ====================

    public List<WorkspaceEntity> listAll() {
        return workspaceMapper.selectList(
                new LambdaQueryWrapper<WorkspaceEntity>().orderByAsc(WorkspaceEntity::getCreateTime));
    }

    /**
     * 查询用户可见的工作区列表（用户是其成员的所有工作区）
     */
    public List<WorkspaceEntity> listByUserId(Long userId) {
        List<WorkspaceMemberEntity> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getUserId, userId));
        if (memberships.isEmpty()) {
            // 至少返回默认工作区
            WorkspaceEntity defaultWs = getBySlug(DEFAULT_SLUG);
            return defaultWs != null ? List.of(defaultWs) : List.of();
        }
        List<Long> wsIds = memberships.stream().map(WorkspaceMemberEntity::getWorkspaceId).toList();
        return workspaceMapper.selectBatchIds(wsIds);
    }

    /**
     * List workspaces visible to a user, each annotated with the user's membership
     * role. Global admins see every workspace (memberRole reflects their real
     * membership, or null when they are not actually a member).
     */
    public List<WorkspaceWithRoleVO> listWithRoleByUserId(Long userId, boolean isGlobalAdmin) {
        List<WorkspaceMemberEntity> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getUserId, userId));
        Map<Long, String> roleByWorkspaceId = new HashMap<>();
        for (WorkspaceMemberEntity m : memberships) {
            roleByWorkspaceId.put(m.getWorkspaceId(), m.getRole());
        }

        List<WorkspaceEntity> entities;
        if (isGlobalAdmin) {
            entities = listAll();
        } else if (memberships.isEmpty()) {
            WorkspaceEntity defaultWs = getBySlug(DEFAULT_SLUG);
            entities = defaultWs != null ? List.of(defaultWs) : List.of();
        } else {
            entities = workspaceMapper.selectBatchIds(roleByWorkspaceId.keySet());
        }

        List<WorkspaceWithRoleVO> result = new ArrayList<>(entities.size());
        for (WorkspaceEntity ws : entities) {
            String role = roleByWorkspaceId.get(ws.getId());
            result.add(WorkspaceWithRoleVO.from(ws, role, isGlobalAdmin));
        }
        return result;
    }

    /**
     * Resolve the user's access summary for a workspace. Used by
     * {@code GET /api/v1/workspaces/&#123;id&#125;/access} so the frontend can
     * refresh its capability set after a role change without reloading the page.
     */
    public WorkspaceAccessVO getAccess(Long workspaceId, Long userId, boolean isGlobalAdmin) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        String memberRole = member != null ? member.getRole() : null;
        String effective = isGlobalAdmin ? "owner" : memberRole;
        Set<String> capabilities = effective != null
                ? RoleCapabilities.forRole(effective)
                : Set.of();
        return new WorkspaceAccessVO(workspaceId, memberRole, isGlobalAdmin, effective, capabilities);
    }

    public WorkspaceEntity getById(Long id) {
        WorkspaceEntity entity = workspaceMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.workspace.not_found", "工作区不存在: " + id);
        }
        return entity;
    }

    public WorkspaceEntity getBySlug(String slug) {
        return workspaceMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceEntity>()
                        .eq(WorkspaceEntity::getSlug, slug));
    }

    /**
     * Derive a URL-safe, unique slug from a workspace name. Non-alphanumeric
     * runs collapse to a single hyphen; a name with no ASCII alphanumerics
     * (e.g. a purely Chinese name) falls back to a generic base. A numeric
     * suffix is appended until the slug is free.
     */
    private String generateUniqueSlug(String name) {
        String base = (name == null ? "" : name.toLowerCase())
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isBlank()) {
            base = "workspace";
        }
        String slug = base;
        int n = 1;
        while (getBySlug(slug) != null) {
            slug = base + "-" + (++n);
        }
        return slug;
    }

    @Transactional
    public WorkspaceEntity create(WorkspaceEntity entity, Long creatorUserId) {
        // Auto-derive a slug from the name when the caller did not supply one,
        // so workspace creation never fails on the NOT NULL slug column.
        if (entity.getSlug() == null || entity.getSlug().isBlank()) {
            entity.setSlug(generateUniqueSlug(entity.getName()));
        }
        // 验证 slug 唯一
        if (getBySlug(entity.getSlug()) != null) {
            throw new MateClawException("err.workspace.slug_exists", "工作区标识已存在: " + entity.getSlug());
        }
        entity.setOwnerId(creatorUserId);
        workspaceMapper.insert(entity);

        // 创建者自动成为 owner
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(entity.getId());
        member.setUserId(creatorUserId);
        member.setRole("owner");
        memberMapper.insert(member);

        // Seed the per-workspace tasks conversation so cron output (now routed
        // there by CronConversationResolver) shows up in the sidebar from day
        // one. The V65 migration handles existing workspaces; this hook covers
        // workspaces created post-upgrade.
        seedTasksConversation(entity.getId());
        seedModelConfiguration(entity.getId());
        // A workspace with no agent leaves Chat / tools / automation / memory
        // unusable; an unset basePath leaves the file-tool sandbox wide open.
        seedDefaultAgent(entity.getId());
        seedBasePath(entity);

        log.info("Created workspace: {} (slug={}, owner={})", entity.getName(), entity.getSlug(), creatorUserId);
        return entity;
    }

    /**
     * Seed one default agent so a brand-new workspace can chat / use tools /
     * run automations from day one. Idempotent: skips when the workspace
     * already has any agent. Non-fatal — workspace creation still succeeds if
     * the seed fails (the user can create an agent manually).
     */
    private void seedDefaultAgent(Long workspaceId) {
        if (workspaceId == null) return;
        try {
            Long existing = agentMapper.selectCount(new LambdaQueryWrapper<AgentEntity>()
                    .eq(AgentEntity::getWorkspaceId, workspaceId));
            if (existing != null && existing > 0) {
                return;
            }
            AgentEntity agent = new AgentEntity();
            agent.setWorkspaceId(workspaceId);
            // msgOptional 缺失时返回 null（不是把 key 原样回写），再硬兜底为「默认助手」，
            // 避免历史上 i18n key 缺失导致 name 变成字面量 "workspace.default_agent.name"。
            String defaultName = i18n != null ? i18n.msgOptional("workspace.default_agent.name") : null;
            agent.setName(defaultName != null && !defaultName.isBlank() ? defaultName : "默认助手");
            agent.setAgentType("react");
            agent.setEnabled(true);
            agent.setIcon("🤖");
            agentMapper.insert(agent);
            log.info("[WorkspaceService] Seeded default agent {} for workspace {}", agent.getId(), workspaceId);
        } catch (Exception e) {
            log.warn("[WorkspaceService] Failed to seed default agent for workspace {}: {}",
                    workspaceId, e.getMessage());
        }
    }

    /**
     * Create the workspace's isolated file-tool sandbox directory and persist
     * its path, unless the caller already supplied one. Non-fatal — a failure
     * here must not block workspace creation. Existing workspaces with a null
     * basePath are handled by a separate (deploy-gated) backfill.
     */
    private void seedBasePath(WorkspaceEntity entity) {
        if (entity.getId() == null) return;
        if (entity.getBasePath() != null && !entity.getBasePath().isBlank()) return;
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(workspaceBaseRoot, String.valueOf(entity.getId()));
            java.nio.file.Files.createDirectories(dir);
            entity.setBasePath(dir.toString());
            workspaceMapper.updateById(entity);
            log.info("[WorkspaceService] Created base path {} for workspace {}", dir, entity.getId());
        } catch (Exception e) {
            log.warn("[WorkspaceService] Failed to create base path for workspace {}: {}",
                    entity.getId(), e.getMessage());
        }
    }

    /**
     * Backfill the basePath of existing workspaces created before basePath
     * seeding existed (their column is null → file-tool sandbox is unbounded).
     * Idempotent: {@link #seedBasePath} skips workspaces that already have one.
     * Run once at startup; a prerequisite for enabling WorkspacePathGuard
     * fail-closed mode (every workspace must have a basePath first).
     *
     * @return number of workspaces that got a freshly-created basePath
     */
    public int backfillMissingBasePaths() {
        int fixed = 0;
        for (WorkspaceEntity ws : listAll()) {
            if (ws.getBasePath() == null || ws.getBasePath().isBlank()) {
                seedBasePath(ws);
                if (ws.getBasePath() != null && !ws.getBasePath().isBlank()) {
                    fixed++;
                }
            }
        }
        return fixed;
    }

    private void seedModelConfiguration(Long workspaceId) {
        if (workspaceId == null || modelProviderService == null) return;
        try {
            modelProviderService.seedWorkspaceModels(workspaceId);
        } catch (Exception e) {
            log.warn("[WorkspaceService] Failed to seed model configuration for workspace {}: {}",
                    workspaceId, e.getMessage());
        }
    }

    private void seedTasksConversation(Long workspaceId) {
        if (workspaceId == null) return;
        ConversationEntity tasks = new ConversationEntity();
        tasks.setConversationId("tasks_" + workspaceId);
        tasks.setTitle(i18n != null ? i18n.msg("cron.tasks_conversation.title") : "📋 Scheduled Tasks");
        tasks.setUsername("system");
        tasks.setMessageCount(0);
        tasks.setLastActiveTime(java.time.LocalDateTime.now());
        tasks.setStreamStatus("idle");
        tasks.setWorkspaceId(workspaceId);
        try {
            conversationMapper.insert(tasks);
        } catch (Exception e) {
            // Non-fatal: workspace creation succeeds even if the seed fails;
            // the conversation will be lazy-created on the first cron save
            // since saveMessage upserts the conversation row.
            log.warn("[WorkspaceService] Failed to seed tasks conversation for workspace {}: {}",
                    workspaceId, e.getMessage());
        }
    }

    public WorkspaceEntity update(WorkspaceEntity entity) {
        WorkspaceEntity existing = getById(entity.getId());
        // slug 为 null 时保留原值，不做修改
        if (entity.getSlug() == null) {
            entity.setSlug(existing.getSlug());
        }
        // 不允许修改默认工作区的 slug
        if (DEFAULT_SLUG.equals(existing.getSlug()) && !DEFAULT_SLUG.equals(entity.getSlug())) {
            throw new MateClawException("err.workspace.cannot_modify_default", "不能修改默认工作区的标识");
        }
        // 验证 slug 唯一性（如果修改了 slug）
        if (!entity.getSlug().equals(existing.getSlug())) {
            if (getBySlug(entity.getSlug()) != null) {
                throw new MateClawException("err.workspace.slug_exists", "工作区标识已存在: " + entity.getSlug());
            }
        }
        workspaceMapper.updateById(entity);
        return entity;
    }

    public void delete(Long id) {
        WorkspaceEntity existing = getById(id);
        if (DEFAULT_SLUG.equals(existing.getSlug())) {
            throw new MateClawException("err.workspace.cannot_delete_default", "不能删除默认工作区");
        }
        // Refuse to delete a workspace that still owns wiki knowledge bases —
        // dropping the workspace row would orphan them. The caller must delete
        // the knowledge bases first.
        int kbCount = wikiKnowledgeBaseService.listByWorkspace(id).size();
        if (kbCount > 0) {
            throw new MateClawException("err.workspace.not_empty", 409,
                    "工作区下还有 " + kbCount + " 个知识库，请先删除知识库再删除工作区");
        }
        workspaceMapper.deleteById(id);
        log.info("Deleted workspace: {} (id={})", existing.getName(), id);
    }

    // ==================== 成员管理 ====================

    public List<WorkspaceMemberEntity> listMembers(Long workspaceId) {
        return memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .orderByAsc(WorkspaceMemberEntity::getCreateTime));
    }

    public WorkspaceMemberEntity getMembership(Long workspaceId, Long userId) {
        return memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMemberEntity::getUserId, userId));
    }

    @Transactional
    public WorkspaceMemberEntity addMember(Long workspaceId, Long userId, String role) {
        // 验证工作区存在
        getById(workspaceId);
        // 检查是否已是成员
        WorkspaceMemberEntity existing = getMembership(workspaceId, userId);
        if (existing != null) {
            throw new MateClawException("err.workspace.member_exists", 409, "用户已经是该工作区的成员");
        }
        String normalizedRole = normalizeAssignableRole(role);
        entitlementService.assertCanAddSubAccount(workspaceId);
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(normalizedRole);
        memberMapper.insert(member);
        evictMembershipCache(workspaceId, userId);
        log.info("Added member to workspace: userId={}, workspaceId={}, role={}", userId, workspaceId, member.getRole());
        return member;
    }

    public WorkspaceMemberEntity updateMemberRole(Long workspaceId, Long userId, String role) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            throw new MateClawException("err.workspace.not_member", 404, "用户不是该工作区的成员");
        }
        if ("owner".equals(member.getRole())) {
            throw new MateClawException("err.workspace.cannot_modify_owner", 400, "不能修改工作区拥有者的角色");
        }
        member.setRole(normalizeAssignableRole(role));
        memberMapper.updateById(member);
        evictMembershipCache(workspaceId, userId);
        return member;
    }

    private String normalizeAssignableRole(String role) {
        String normalized = role == null || role.isBlank() ? "member" : role.trim();
        return switch (normalized) {
            case "admin", "member", "viewer" -> normalized;
            case "owner" -> throw new MateClawException(
                    "err.workspace.invalid_member_role", 400, "不能通过成员管理授予 owner 角色");
            default -> throw new MateClawException(
                    "err.workspace.invalid_member_role", 400, "无效的成员角色: " + normalized);
        };
    }

    public void removeMember(Long workspaceId, Long userId) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            throw new MateClawException("err.workspace.not_member", 404, "用户不是该工作区的成员");
        }
        if ("owner".equals(member.getRole())) {
            throw new MateClawException("err.workspace.cannot_remove_owner", 400, "不能移除工作区拥有者");
        }
        memberMapper.deleteById(member.getId());
        evictMembershipCache(workspaceId, userId);
        log.info("Removed member from workspace: userId={}, workspaceId={}", userId, workspaceId);
    }

    // ==================== 权限检查 ====================

    /**
     * 检查用户是否有指定工作区的最低角色权限
     *
     * @param workspaceId 工作区 ID
     * @param userId      用户 ID
     * @param minRole     最低角色要求：owner > admin > member > viewer
     * @return true 如果用户有足够权限
     */
    public boolean hasPermission(Long workspaceId, Long userId, String minRole) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            return false;
        }
        return roleLevel(member.getRole()) >= roleLevel(minRole);
    }

    /**
     * 断言用户有指定权限，否则抛异常
     */
    public void requirePermission(Long workspaceId, Long userId, String minRole) {
        if (!hasPermission(workspaceId, userId, minRole)) {
            throw new MateClawException("err.workspace.insufficient_permission", 403, "权限不足：需要 " + minRole + " 或更高角色");
        }
    }

    /**
     * 带缓存的权限检查（拦截器高频调用，避免每次请求查库）
     */
    public boolean hasPermissionCached(Long workspaceId, Long userId, String minRole) {
        String cacheKey = workspaceId + ":" + userId;
        String role = membershipCache.get(cacheKey, k -> {
            WorkspaceMemberEntity member = getMembership(workspaceId, userId);
            return member != null ? member.getRole() : "";
        });
        if (role == null || role.isEmpty()) {
            return false;
        }
        return roleLevel(role) >= roleLevel(minRole);
    }

    /**
     * 清除指定 workspace + user 的成员资格缓存（成员变更时调用）
     */
    public void evictMembershipCache(Long workspaceId, Long userId) {
        membershipCache.invalidate(workspaceId + ":" + userId);
    }

    private int roleLevel(String role) {
        return switch (role) {
            case "owner" -> 4;
            case "admin" -> 3;
            case "member" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
