package vip.mate.workflow.v2;

import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.AgentRunStatus;
import vip.mate.os.run.model.AgentStepStatus;
import vip.mate.os.run.runtime.AgentRunKernel;
import vip.mate.os.run.runtime.AgentRunRequest;
import vip.mate.os.run.runtime.AgentStepRequest;
import vip.mate.os.run.runtime.RunEvent;
import vip.mate.os.run.runtime.RunEventPublisher;
import vip.mate.os.run.runtime.StepCloseRequest;
import vip.mate.os.run.runtime.StepLedgerService;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultWorkflowRuntimeV2 implements WorkflowRuntimeV2 {

    private final AgentRunKernel kernel;
    private final StepLedgerService steps;
    private final RunEventPublisher events;
    private final List<WorkflowStepExecutorV2> executors;

    public DefaultWorkflowRuntimeV2(AgentRunKernel kernel,
                                    StepLedgerService steps,
                                    RunEventPublisher events,
                                    List<WorkflowStepExecutorV2> executors) {
        this.kernel = kernel;
        this.steps = steps;
        this.events = events;
        this.executors = executors;
    }

    @Override
    public AgentRunEntity start(WorkflowV2Graph graph, Map<String, Object> input) {
        AgentRunEntity run = kernel.createRun(new AgentRunRequest(
                resolveWorkspaceId(input),
                resolveLong(input.get("conversationId")),
                String.valueOf(input.getOrDefault("traceId", "")),
                "workflow.v2",
                "workflow",
                graph.id(),
                null,
                resolveLong(input.get("createdBy"))));
        kernel.startRun(run.getId());
        events.publish(new RunEvent(run.getId(), null, "workflow_v2_started", "info",
                "{\"workflowId\":\"" + graph.id() + "\",\"entry\":\"" + graph.entry() + "\"}",
                null));

        WorkflowV2StepSpec entry = graph.steps().stream()
                .filter(step -> graph.entry().equals(step.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("entry step not found: " + graph.entry()));
        var step = steps.openStep(new AgentStepRequest(
                run.getId(), null, entry.id(), null, entry.type().wire(),
                entry.idempotencyKey(), policyTags(entry), null));

        Optional<WorkflowStepExecutorV2> executor = executors.stream()
                .filter(candidate -> candidate.type() == entry.type())
                .findFirst();
        if (executor.isEmpty()) {
            steps.closeStep(new StepCloseRequest(step.getId(), AgentStepStatus.FAILED, null, null,
                    "WORKFLOW_V2_EXECUTOR_MISSING",
                    "No executor registered for " + entry.type().wire(),
                    null, null));
            return kernel.finishFailed(run.getId(), "WORKFLOW_V2_EXECUTOR_MISSING",
                    "No executor registered for " + entry.type().wire());
        }

        WorkflowStepResultV2 result = executor.get().execute(entry,
                new WorkflowExecutionContextV2(run.getId(), run.getWorkspaceId(), input));
        AgentStepStatus status = switch (result.status()) {
            case SUCCEEDED -> AgentStepStatus.SUCCEEDED;
            case FAILED -> AgentStepStatus.FAILED;
            case PAUSED -> AgentStepStatus.PAUSED;
            case SKIPPED -> AgentStepStatus.SKIPPED;
        };
        steps.closeStep(new StepCloseRequest(step.getId(), status, result.outputRef(), null,
                result.failureCode(), result.failureMessage(), null, null));
        if (status == AgentStepStatus.SUCCEEDED || status == AgentStepStatus.SKIPPED) {
            return kernel.finishSucceeded(run.getId(), result.outputRef());
        }
        if (status == AgentStepStatus.PAUSED) {
            return kernel.transition(run.getId(), AgentRunStatus.PAUSED);
        }
        return kernel.finishFailed(run.getId(), result.failureCode(), result.failureMessage());
    }

    static Long resolveWorkspaceId(Map<String, Object> input) {
        Long value = resolveLong(input.get("workspaceId"));
        if (value != null) {
            return value;
        }
        // Off-request fallback: a workflow started from cron/async carries its
        // workspace on the holder. Fail-closed when both are absent — never
        // silently default to workspace 1, which would cross-tenant the run.
        Long bound = WorkspaceContextHolder.get();
        if (bound != null) {
            return bound;
        }
        throw new IllegalStateException(
                "workflow.v2 start requires a workspaceId: both the input and WorkspaceContextHolder "
                        + "are empty; refusing to default to workspace 1");
    }

    private static Long resolveLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s);
        }
        return null;
    }

    private static String policyTags(WorkflowV2StepSpec step) {
        return step.policy() == null || step.policy().isNull() ? null : step.policy().toString();
    }
}
