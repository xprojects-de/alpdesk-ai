package de.alpdesk.services.tools;

import de.alpdesk.entities.mcp.TenantMcpKey;
import de.alpdesk.entities.tenant.TenantApiKey;
import de.alpdesk.repositories.mcp.TenantMcpKeyRepository;
import de.alpdesk.repositories.tenant.TenantApiKeyRepository;
import de.alpdesk.services.tenant.UserGroupService;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

/**
 * Who may search the web in the chat (docs/architecture/ai-features.md "Web search"): a user through one of its groups,
 * the client software through its API key, the MCP server through its switch. A scheduled task is not asked here: its
 * text is its switch (web: true on an assistant's prompt step, set by PromptStep itself), never otherwise. Whether the
 * tenant has the web search at all is the tool's question (its catalog entry and key).
 */
@Singleton
public class WebSearchAccess {

    private final UserGroupService groups;
    private final TenantApiKeyRepository apiKeys;
    private final TenantMcpKeyRepository mcpKeys;

    public WebSearchAccess(UserGroupService groups, TenantApiKeyRepository apiKeys, TenantMcpKeyRepository mcpKeys) {
        this.groups = groups;
        this.apiKeys = apiKeys;
        this.mcpKeys = mcpKeys;
    }

    /** keyId: the client software's API key, or the MCP key the MCP server was asked with. */
    public boolean allows(ToolContext.Asker asker, @Nullable Long userId, @Nullable Long keyId) {
        return switch (asker) {
            case USER -> userId != null && groups.searchesWeb(userId);
            case CLIENT_SOFTWARE -> keyId != null && apiKeys.findById(keyId).map(TenantApiKey::webSearch).orElse(false);
            case MCP_SERVER -> keyId != null && mcpKeys.findById(keyId).map(TenantMcpKey::webSearch).orElse(false);
            case TASK -> false;
        };
    }
}
