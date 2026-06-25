package vip.mate.workspace.document.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区文件实体（Agent 级 Markdown 文档）
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_workspace_file")
public class WorkspaceFileEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的 Agent ID */
    private Long agentId;

    /**
     * 隔离工作区 ID（V149）。非内置 Agent = 该 Agent 自身工作区；内置(全局)Agent
     * = 调用方运行工作区，使内置数字员工的记忆按工作区各存各的、不跨租户串台。
     */
    private Long workspaceId;

    /** 文件名（如 AGENTS.md、SOUL.md） */
    private String filename;

    /** Markdown 内容 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 是否启用为系统提示词 */
    private Boolean enabled;

    /** 排序顺序（越小越靠前） */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
