package vip.mate.lead.douyin;

import org.springframework.stereotype.Service;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionRunResponse;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.store.LeadPersistenceService;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.runtime.AgentRunKernel;
import vip.mate.os.run.runtime.AgentRunRequest;
import vip.mate.os.run.runtime.RunCancellationService;
import vip.mate.os.run.model.LeadTaskEntity;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DouyinLeadAcquisitionRunService {

    private final AgentRunKernel runKernel;
    private final RunCancellationService cancellationService;
    private final LeadPersistenceService persistence;
    private final DouyinLeadAcquisitionExecutor executor;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public DouyinLeadAcquisitionRunService(AgentRunKernel runKernel,
                                           RunCancellationService cancellationService,
                                           LeadPersistenceService persistence,
                                           DouyinLeadAcquisitionExecutor executor) {
        this.runKernel = runKernel;
        this.cancellationService = cancellationService;
        this.persistence = persistence;
        this.executor = executor;
    }

    public DouyinLeadAcquisitionRunResponse start(Long workspaceId, Long createdBy, DouyinLeadAcquisitionInput input) {
        RunAndTask created = createRunAndTask(workspaceId, createdBy, input);
        Long runId = created.run().getId();
        Long taskId = created.task().getId();
        worker.submit(() -> executor.execute(runId, taskId, input));
        return DouyinLeadAcquisitionRunResponse.started(runId, taskId, created.run().getStatus());
    }

    public DouyinLeadAcquisitionRunResponse runSync(Long workspaceId, Long createdBy, DouyinLeadAcquisitionInput input,
                                                    vip.mate.lead.douyin.api.DouyinLeadAcquisitionQueryService queryService) {
        RunAndTask created = createRunAndTask(workspaceId, createdBy, input);
        executor.execute(created.run().getId(), created.task().getId(), input);
        return queryService.byRun(created.run().getId());
    }

    private RunAndTask createRunAndTask(Long workspaceId, Long createdBy, DouyinLeadAcquisitionInput input) {
        AgentRunEntity run = runKernel.createRun(new AgentRunRequest(
                workspaceId == null ? 1L : workspaceId,
                null,
                UUID.randomUUID().toString(),
                "skill.douyin.lead_acquisition",
                "skill",
                "douyin.lead_acquisition",
                null,
                createdBy));
        LeadTaskEntity task = persistence.createTask(run.getId(), run.getWorkspaceId(), input);
        return new RunAndTask(run, task);
    }

    public void cancel(Long runId) {
        // 仅置取消标志(协作式)。执行线程在评论滚动循环、分片 sleep 与 assertNotCancelled 检查点处
        // 主动收口并把任务置为 ABORTED。绝不再 interrupt 执行线程 —— 它同时在做 H2 JDBC 写,Java NIO
        // 中断会关闭 H2 共享 FileChannel,导致整库 "The database has been closed"、后端虽不退出却彻底
        // 瘫痪(就是"停止任务→服务被kill"的真因;详见 LeadRunContext)。响应性由"分片 sleep 每片
        // 检查 + 循环每轮检查"保证,不再依赖中断。
        cancellationService.requestCancel(runId);
        runKernel.requestCancel(runId);
    }

    private record RunAndTask(AgentRunEntity run, LeadTaskEntity task) {
    }
}
