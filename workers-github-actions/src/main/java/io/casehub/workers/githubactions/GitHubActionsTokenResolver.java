package io.casehub.workers.githubactions;

import io.casehub.workers.common.PermanentFaultException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GitHubActionsTokenResolver {

    @ConfigProperty(name = "casehub.workers.github-actions.token")
    Optional<String> globalToken;

    @ConfigProperty(name = "casehub.workers.github-actions.tokens", defaultValue = "")
    Map<String, String> orgTokens;

    @ConfigProperty(name = "casehub.workers.github-actions.api-base-url",
                    defaultValue = "https://api.github.com")
    String apiBaseUrl;

    public String resolve(String owner) {
        String orgToken = orgTokens.get(owner);
        if (orgToken != null && !orgToken.isBlank()) {
            return orgToken;
        }
        return globalToken
            .filter(t -> !t.isBlank())
            .orElseThrow(() -> new PermanentFaultException(0,
                "No GitHub token configured for org '" + owner
                    + "' and no global fallback (casehub.workers.github-actions.token)"));
    }

    public boolean hasToken() {
        return globalToken.isPresent() && !globalToken.get().isBlank();
    }

    public String apiBaseUrl() {
        return apiBaseUrl;
    }
}
