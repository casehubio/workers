package io.casehub.workers.mcp;

import java.util.Set;

record ServerInitResult(String serverName, boolean success, McpSession session,
                        Set<String> discoveredTools, Throwable error) {

    static ServerInitResult success(String serverName, McpSession session, Set<String> discoveredTools) {
        return new ServerInitResult(serverName, true, session, discoveredTools, null);
    }

    static ServerInitResult failure(String serverName, Throwable error) {
        return new ServerInitResult(serverName, false, null, Set.of(), error);
    }
}
