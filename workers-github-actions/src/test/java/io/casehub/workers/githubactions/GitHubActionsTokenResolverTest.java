package io.casehub.workers.githubactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.workers.common.PermanentFaultException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitHubActionsTokenResolverTest {

    @Test
    void globalToken_resolves() {
        GitHubActionsTokenResolver resolver = new GitHubActionsTokenResolver();
        resolver.globalToken = Optional.of("ghp_global");
        resolver.orgTokens = Map.of();

        assertThat(resolver.resolve("any-org")).isEqualTo("ghp_global");
    }

    @Test
    void perOrgToken_takesPrecedence() {
        GitHubActionsTokenResolver resolver = new GitHubActionsTokenResolver();
        resolver.globalToken = Optional.of("ghp_global");
        resolver.orgTokens = Map.of("casehubio", "ghp_casehub");

        assertThat(resolver.resolve("casehubio")).isEqualTo("ghp_casehub");
    }

    @Test
    void perOrgMiss_fallsBackToGlobal() {
        GitHubActionsTokenResolver resolver = new GitHubActionsTokenResolver();
        resolver.globalToken = Optional.of("ghp_global");
        resolver.orgTokens = Map.of("other-org", "ghp_other");

        assertThat(resolver.resolve("casehubio")).isEqualTo("ghp_global");
    }

    @Test
    void noToken_throwsPermanentFault() {
        GitHubActionsTokenResolver resolver = new GitHubActionsTokenResolver();
        resolver.globalToken = Optional.empty();
        resolver.orgTokens = Map.of();

        assertThatThrownBy(() -> resolver.resolve("casehubio"))
            .isInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("casehubio");
    }

    @Test
    void noGlobal_perOrgMiss_throwsPermanentFault() {
        GitHubActionsTokenResolver resolver = new GitHubActionsTokenResolver();
        resolver.globalToken = Optional.empty();
        resolver.orgTokens = Map.of("other-org", "ghp_other");

        assertThatThrownBy(() -> resolver.resolve("casehubio"))
            .isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void hasToken_globalExists() {
        GitHubActionsTokenResolver resolver = new GitHubActionsTokenResolver();
        resolver.globalToken = Optional.of("ghp_global");
        resolver.orgTokens = Map.of();

        assertThat(resolver.hasToken()).isTrue();
    }

    @Test
    void hasToken_noGlobal() {
        GitHubActionsTokenResolver resolver = new GitHubActionsTokenResolver();
        resolver.globalToken = Optional.empty();
        resolver.orgTokens = Map.of();

        assertThat(resolver.hasToken()).isFalse();
    }
}
