package vip.mate.dashboard.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.repository.CronJobMapper;
import vip.mate.dashboard.service.CronJobRunService;
import vip.mate.dashboard.service.DashboardService;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardControllerWorkspaceIsolationTest {

    private CronJobRunService cronJobRunService;
    private CronJobMapper cronJobMapper;
    private DashboardController controller;

    @BeforeEach
    void setUp() {
        cronJobRunService = mock(CronJobRunService.class);
        cronJobMapper = mock(CronJobMapper.class);
        controller = new DashboardController(
                mock(DashboardService.class),
                cronJobRunService,
                cronJobMapper);
    }

    @Test
    void cronJobRunsFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.cronJobRuns(42L, null, 20));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(cronJobMapper, cronJobRunService);
    }

    @Test
    void recentRunsFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.recentRuns(null, 20));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(cronJobRunService);
    }

    @Test
    void cronJobRunsRejectsJobFromDifferentWorkspace() {
        CronJobEntity job = new CronJobEntity();
        job.setId(42L);
        job.setWorkspaceId(2L);
        when(cronJobMapper.selectById(42L)).thenReturn(job);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.cronJobRuns(42L, 1L, 20));

        assertEquals(403, ex.getCode());
        assertEquals("err.common.wrong_workspace", ex.getMsgKey());
        verifyNoInteractions(cronJobRunService);
    }
}
