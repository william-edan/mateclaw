package vip.mate.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 租户行级隔离处理器：把「手写易漏的 {@code .eq(workspaceId)}」变成
 * 「拦截器默认注入」。配合 {@link com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor}
 * 在 SELECT/UPDATE/DELETE 的 WHERE 注入 {@code workspace_id = <当前工作区>}。
 *
 * <p><b>灰度白名单</b>：只有 {@code enabledTables} 中的表被注入谓词，其余一律
 * {@link #ignoreTable} 放行（不改写）。这样拦截器即便注册，未启用的表行为完全不变，
 * 可以逐表灰度。启用一张表前必须确认：① 该表已有 {@code workspace_id} 列（Part 4）；
 * ② 该表所有读写路径的工作区上下文已可靠绑定（off-request + reactive，否则
 * {@link #getTenantId()} 的 fail-closed 会打挂 cron/workflow/agent）；③ 跑全量回归。
 *
 * <p><b>INSERT 注意</b>：TenantLineInnerInterceptor 默认会给被拦截表的 INSERT 注入
 * 当前工作区值，可能覆盖 seed/copy 显式写入的目标工作区。启用此类表前需评估，必要时
 * 把它们排除在白名单外，或用 {@code WorkspaceContextHolder.runWith(目标ws, ...)} 包裹写路径。
 */
public class WorkspaceTenantLineHandler implements TenantLineHandler {

    private final Set<String> enabledTables;

    public WorkspaceTenantLineHandler(Set<String> enabledTables) {
        this.enabledTables = enabledTables == null ? Set.of()
                : enabledTables.stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(t -> t.trim().toLowerCase())
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Expression getTenantId() {
        Long workspaceId = WorkspaceContextHolder.get();
        if (workspaceId == null) {
            // fail-closed：绝不回落 workspace=1。只有白名单表会触达这里，所以启用某表前
            // 它的所有路径必须已绑上下文（Part 1 off-request + Part 5 reactive）。
            throw new IllegalStateException(
                    "TenantLine: 工作区上下文缺失，拒绝对租户表执行未限定的 SQL（fail-closed）");
        }
        return new LongValue(workspaceId);
    }

    @Override
    public String getTenantIdColumn() {
        return "workspace_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 白名单外的表（含 null）一律放行，不注入租户谓词。
        return tableName == null || !enabledTables.contains(tableName.toLowerCase());
    }
}
