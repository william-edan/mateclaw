package vip.mate.workspace.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;

/**
 * 统一的 {@code agentId → 工作区} 归属校验（fail-closed）。
 *
 * <p>许多资源（plan、fact、memory、dream …）自身没有 {@code workspace_id} 列，
 * 归属唯一来源是其 {@code agentId} 对应 {@link AgentEntity#getWorkspaceId()}。
 * 此前 planning / agent-binding 各写一份校验，且对「agent 不存在 / agentId 非数字」
 * 短路放行（越权破口）。本组件收敛为单一 fail-closed 实现：取不到归属一律拒绝，
 * 绝不放行、绝不回落默认工作区。
 */
@Component
@RequiredArgsConstructor
public class AgentWorkspaceVerifier {

    private final AgentMapper agentMapper;

    /** 校验数字 {@code agentId} 归属 {@code workspaceId}，不符（含 agent 不存在）抛 403。 */
    public void verify(Long agentId, long workspaceId) {
        Long agentWorkspaceId = resolve(agentId);
        if (agentWorkspaceId == null || agentWorkspaceId != workspaceId) {
            throw new MateClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
    }

    /** 字符串 {@code agentId} 版本；空 / 非数字一律 fail-closed 拒绝。 */
    public void verify(String agentId, long workspaceId) {
        verify(parse(agentId), workspaceId);
    }

    /**
     * 解析 agent 所属工作区，供「按工作区过滤集合」场景使用（不抛异常）。
     * agent 不存在 / agentId 为空或非数字时返回 {@code null}，调用方据此跳过。
     */
    public Long resolveWorkspace(String agentId) {
        return resolve(parse(agentId));
    }

    private Long resolve(Long agentId) {
        if (agentId == null) {
            return null;
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        return agent == null ? null : agent.getWorkspaceId();
    }

    private Long parse(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(agentId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
