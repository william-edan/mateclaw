package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration must leave a new workspace usable: a default agent (else
 * Chat / tools / automation / memory are all dead) and an isolated basePath
 * directory (else the file-tool sandbox is unbounded).
 */
class WorkspaceServiceSeedTest {

    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final WorkspaceMemberMapper memberMapper = mock(WorkspaceMemberMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final WikiKnowledgeBaseService wikiKnowledgeBaseService = mock(WikiKnowledgeBaseService.class);
    private final AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
    private final ModelProviderService modelProviderService = mock(ModelProviderService.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final WorkspaceService service = new WorkspaceService(
            workspaceMapper, memberMapper, conversationMapper, wikiKnowledgeBaseService, null,
            entitlementService, modelProviderService, agentMapper);

    @TempDir
    Path tempRoot;

    @BeforeAll
    static void initMyBatisPlusCache() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), WorkspaceEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentEntity.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "workspaceBaseRoot", tempRoot.toString());
        when(workspaceMapper.selectOne(any())).thenReturn(null); // slug uniqueness check passes
    }

    private WorkspaceEntity newWorkspace() {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setName("Acme");
        ws.setSlug("acme");
        ws.setId(7L); // simulate the id assigned by insert (mock is a no-op)
        return ws;
    }

    @Test
    void createSeedsDefaultAgentBoundToWorkspace() {
        service.create(newWorkspace(), 42L);

        ArgumentCaptor<AgentEntity> captor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentMapper).insert(captor.capture());
        AgentEntity seeded = captor.getValue();
        assertThat(seeded.getWorkspaceId()).isEqualTo(7L);
        assertThat(seeded.getName()).isNotBlank();
        assertThat(seeded.getEnabled()).isTrue();
        assertThat(seeded.getAgentType()).isEqualTo("react");
    }

    @Test
    void createCreatesAndPersistsBasePathDirectory() {
        WorkspaceEntity ws = newWorkspace();

        service.create(ws, 42L);

        assertThat(ws.getBasePath()).isNotBlank();
        assertThat(Files.isDirectory(Path.of(ws.getBasePath())))
                .as("base path directory must be created on disk")
                .isTrue();
        assertThat(Path.of(ws.getBasePath())).isEqualTo(tempRoot.resolve("7"));
        verify(workspaceMapper).updateById(ws); // basePath persisted back
    }

    @Test
    void createSkipsDefaultAgentWhenWorkspaceAlreadyHasOne() {
        when(agentMapper.selectCount(any())).thenReturn(1L);

        service.create(newWorkspace(), 42L);

        verify(agentMapper, org.mockito.Mockito.never()).insert(any(AgentEntity.class));
    }

    @Test
    void backfillMissingBasePathsFixesOnlyNullOnes() {
        WorkspaceEntity withPath = new WorkspaceEntity();
        withPath.setId(1L);
        withPath.setBasePath("/already/set");
        WorkspaceEntity withoutPath = new WorkspaceEntity();
        withoutPath.setId(2L);
        when(workspaceMapper.selectList(any())).thenReturn(java.util.List.of(withPath, withoutPath));

        int fixed = service.backfillMissingBasePaths();

        assertThat(fixed).isEqualTo(1);
        assertThat(withPath.getBasePath()).isEqualTo("/already/set"); // untouched
        assertThat(withoutPath.getBasePath()).isEqualTo(tempRoot.resolve("2").toString());
        assertThat(Files.isDirectory(Path.of(withoutPath.getBasePath()))).isTrue();
    }
}
