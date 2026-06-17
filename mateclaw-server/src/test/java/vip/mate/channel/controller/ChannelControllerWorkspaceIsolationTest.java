package vip.mate.channel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.verifier.ChannelVerifierRegistry;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChannelControllerWorkspaceIsolationTest {

    private ChannelService channelService;
    private ChannelController controller;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        controller = new ChannelController(
                channelService,
                mock(ChannelManager.class),
                mock(AuditEventService.class),
                mock(ChannelVerifierRegistry.class),
                new ObjectMapper());
    }

    @Test
    void listFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.list(null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(channelService);
    }

    @Test
    void getFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.get(42L, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(channelService);
    }

    @Test
    void createFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.create(null, new ChannelEntity()));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(channelService);
    }

    @Test
    void getRejectsChannelFromDifferentWorkspace() {
        ChannelEntity channel = new ChannelEntity();
        channel.setId(42L);
        channel.setWorkspaceId(2L);
        when(channelService.getChannel(42L)).thenReturn(channel);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.get(42L, 1L));

        assertEquals(403, ex.getCode());
        assertEquals("err.common.wrong_workspace", ex.getMsgKey());
    }
}
