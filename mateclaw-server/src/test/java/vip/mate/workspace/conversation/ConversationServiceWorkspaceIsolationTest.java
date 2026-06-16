package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.channel.repository.ChannelSessionMapper;
import vip.mate.task.repository.AsyncTaskMapper;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cross-workspace isolation for {@code isConversationOwner}. The per-conversation
 * endpoints (messages, delete, rename, clear, ...) gate solely on this check, so
 * it must verify the conversation lives in the caller's workspace — otherwise a
 * member of workspace A can read/mutate workspace B's conversations by id, and
 * every {@code system}-owned conversation leaks to all users regardless of workspace.
 */
class ConversationServiceWorkspaceIsolationTest {

    private ConversationMapper conversationMapper;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        service = new ConversationService(
                conversationMapper,
                mock(MessageMapper.class),
                mock(AgentMapper.class),
                new ObjectMapper(),
                mock(ToolApprovalMapper.class),
                mock(AsyncTaskMapper.class),
                mock(ChannelSessionMapper.class),
                mock(ApplicationEventPublisher.class));
    }

    private void givenConversation(String username, Long workspaceId) {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId("c1");
        conv.setUsername(username);
        conv.setWorkspaceId(workspaceId);
        when(conversationMapper.selectOne(any())).thenReturn(conv);
    }

    @Test
    void ownerInSameWorkspaceIsAllowed() {
        givenConversation("alice", 1L);
        assertTrue(service.isConversationOwner("c1", "alice", 1L));
    }

    @Test
    void ownerButForeignWorkspaceIsDenied() {
        givenConversation("alice", 99L);
        assertFalse(service.isConversationOwner("c1", "alice", 1L),
                "the same user must not reach a conversation that lives in another workspace");
    }

    @Test
    void systemConversationFromOtherWorkspaceIsDenied() {
        givenConversation("system", 99L);
        assertFalse(service.isConversationOwner("c1", "alice", 1L),
                "system conversations must be scoped to their own workspace, not visible to everyone");
    }

    @Test
    void systemConversationInSameWorkspaceIsVisible() {
        givenConversation("system", 1L);
        assertTrue(service.isConversationOwner("c1", "alice", 1L));
    }

    @Test
    void differentUserIsDenied() {
        givenConversation("bob", 1L);
        assertFalse(service.isConversationOwner("c1", "alice", 1L));
    }
}
