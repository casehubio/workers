package io.casehub.workers.githubactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.workers.common.WorkerProvisioningException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubActionsReactiveWorkerProvisionerTest {

    private GitHubActionsReactiveWorkerProvisioner provisioner;
    private GitHubActionsTokenResolver tokenResolver;

    @BeforeEach
    void setUp() {
        tokenResolver = mock(GitHubActionsTokenResolver.class);
        provisioner = new GitHubActionsReactiveWorkerProvisioner();
        provisioner.tokenResolver = tokenResolver;
    }

    @Test
    void getCapabilities_returnsBothTags() {
        assertThat(provisioner.getCapabilities().await().indefinitely())
            .containsExactlyInAnyOrder(
                GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH,
                GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);
    }

    @Test
    void provision_workflowDispatch_succeeds() {
        when(tokenResolver.hasToken()).thenReturn(true);
        ProvisionContext ctx = new ProvisionContext(UUID.randomUUID(), "platform", "task", null, null, null, null);
        ProvisionResult result = provisioner.provision(
            Set.of(GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH), ctx)
            .await().indefinitely();
        assertThat(result).isNotNull();
    }

    @Test
    void provision_repositoryDispatch_succeeds() {
        when(tokenResolver.hasToken()).thenReturn(true);
        ProvisionContext ctx = new ProvisionContext(UUID.randomUUID(), "platform", "task", null, null, null, null);
        ProvisionResult result = provisioner.provision(
            Set.of(GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH), ctx)
            .await().indefinitely();
        assertThat(result).isNotNull();
    }

    @Test
    void provision_unknownCapability_throws() {
        when(tokenResolver.hasToken()).thenReturn(true);
        ProvisionContext ctx = new ProvisionContext(UUID.randomUUID(), "platform", "task", null, null, null, null);
        assertThatThrownBy(() -> provisioner.provision(
            Set.of("unknown-capability"), ctx).await().indefinitely())
            .isInstanceOf(WorkerProvisioningException.class);
    }

    @Test
    void provision_noToken_throws() {
        when(tokenResolver.hasToken()).thenReturn(false);
        ProvisionContext ctx = new ProvisionContext(UUID.randomUUID(), "platform", "task", null, null, null, null);
        assertThatThrownBy(() -> provisioner.provision(
            Set.of(GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH), ctx)
            .await().indefinitely())
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("token");
    }

    @Test
    void terminate_returnsVoid() {
        assertThat(provisioner.terminate("any", "tenant-1").await().indefinitely()).isNull();
    }
}
