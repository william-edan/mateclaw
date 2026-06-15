package vip.mate.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.llm.service.ModelWorkspaceResolver;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.lifecycle.MemoryLifecycleMediator;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceWorkspaceContextTest {

    @Mock private AgentMapper agentMapper;
    @Mock private AgentGraphBuilder agentGraphBuilder;
    @Mock private MemoryRecallTracker memoryRecallTracker;
    @Mock private MemoryManager memoryManager;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConversationMapper conversationMapper;

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        MemoryLifecycleMediator mediator = new MemoryLifecycleMediator(memoryManager, eventPublisher);
        agentService = new AgentService(agentMapper, agentGraphBuilder,
                memoryRecallTracker, mediator, new MemoryProperties(), conversationMapper);
    }

    @AfterEach
    void tearDown() {
        ModelWorkspaceResolver.clear();
    }

    @Test
    void chatBuildsAgentInsideConversationWorkspaceWhenOriginIsEmpty() {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId("conv-20");
        conversation.setWorkspaceId(20L);
        when(conversationMapper.selectOne(any())).thenReturn(conversation);

        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setEnabled(true);
        when(agentMapper.selectById(7L)).thenReturn(agent);

        BaseAgent built = mock(BaseAgent.class);
        when(agentGraphBuilder.build(any(AgentEntity.class), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    assertEquals(20L, ModelWorkspaceResolver.currentWorkspaceId());
                    return built;
                });
        when(built.chat("hello", "conv-20")).thenReturn("hi");

        assertEquals("hi", agentService.chat(7L, "hello", "conv-20"));
    }
}
