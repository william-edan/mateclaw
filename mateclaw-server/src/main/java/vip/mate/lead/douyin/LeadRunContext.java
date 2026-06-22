package vip.mate.lead.douyin;

import java.util.function.BooleanSupplier;

/**
 * 当前抖音获客 run 的【协作式取消】信号(ThreadLocal)。
 *
 * <p>背景(根治"停止任务→服务被kill"):旧的 {@code DouyinLeadAcquisitionRunService.cancel}
 * 用 {@code runningThread.interrupt()} 让执行线程的长 sleep / 评论滚动循环立即跳出。但执行线程
 * 同时也在做 H2 的 JDBC 写;Java NIO 的硬特性是——<b>中断一个正在对 {@code FileChannel} 做 I/O
 * 的线程会直接关闭该 channel</b>({@code ClosedByInterruptException})。H2 MVStore 整库共享同一个
 * {@code FileChannel},于是一次 interrupt 就把整库的文件通道关掉,之后所有查询都 "The database
 * has been closed",后端虽未退出却彻底不可用(socket 模式的 MySQL 不受此影响,故仅桌面 H2 中招)。
 *
 * <p>修法:彻底不再 interrupt 执行线程,改成协作式取消——执行器在 run 线程入口 {@link #set} 一个
 * "查 {@code RunCancellationService} 取消标志" 的 supplier,适配器在长循环与分片 sleep 里调
 * {@link #isCancelled()} 主动跳出。响应性靠"分片 + 每片检查"保证,且绝不触碰 H2 的文件通道。
 *
 * <p>线程模型:run 在 {@code newVirtualThreadPerTaskExecutor} 的虚拟线程上同步执行,适配器方法在
 * 同一线程的调用栈内被调用,故 ThreadLocal 对适配器可见。{@code cancel()} 在 HTTP 线程上只是把
 * 取消标志写进共享的 {@code RunCancellationService}(并发安全 Set),由 run 线程这边读到。
 */
public final class LeadRunContext {

    private static final ThreadLocal<BooleanSupplier> CANCELLED = new ThreadLocal<>();

    private LeadRunContext() {
    }

    /** run 线程入口设置取消查询器(执行器持有 runId + RunCancellationService)。 */
    public static void set(BooleanSupplier cancelledCheck) {
        CANCELLED.set(cancelledCheck);
    }

    /** 当前 run 是否已被请求取消;无上下文(非获客调用路径)时返回 false。 */
    public static boolean isCancelled() {
        BooleanSupplier s = CANCELLED.get();
        return s != null && s.getAsBoolean();
    }

    /** run 结束在 finally 清理,杜绝 ThreadLocal 跨线程复用残留。 */
    public static void clear() {
        CANCELLED.remove();
    }
}
