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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DouyinLeadAcquisitionRunService {

    private final AgentRunKernel runKernel;
    private final RunCancellationService cancellationService;
    private final LeadPersistenceService persistence;
    private final DouyinLeadAcquisitionExecutor executor;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, Thread> runningThreads = new ConcurrentHashMap<>();

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
        worker.submit(() -> {
            runningThreads.put(runId, Thread.currentThread());
            try {
                executor.execute(runId, taskId, input);
            } finally {
                runningThreads.remove(runId, Thread.currentThread());
            }
        });
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
        cancellationService.requestCancel(runId);
        runKernel.requestCancel(runId);
        Thread runningThread = runningThreads.get(runId);
        if (runningThread != null) {
            // 中断执行线程：让正在进行的评论采集滚动循环 / sleep 立即跳出，
            // 配合 assertNotCancelled 检查点把任务快速置为 ABORTED，
            // 避免等到整段视频采集结束才响应取消（这是“停止延迟很大”的根因）。
            runningThread.interrupt();
        }
    }

    private record RunAndTask(AgentRunEntity run, LeadTaskEntity task) {
    }
}
