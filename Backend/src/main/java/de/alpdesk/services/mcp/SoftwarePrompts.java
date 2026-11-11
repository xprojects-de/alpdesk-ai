package de.alpdesk.services.mcp;

import de.alpdesk.dto.mcp.McpPromptArgument;
import de.alpdesk.dto.mcp.McpPromptResponse;
import de.alpdesk.dto.mcp.SoftwarePromptResponse;
import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.entities.mcp.McpPrompt;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import de.alpdesk.repositories.mcp.McpPromptRepository;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.UserGroupService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The prompts of the tenant's connected software in a user's chat (docs/architecture/mcp.md "Prompts of the connected
 * software"): the ones a tenant admin turned on, of the connections the user has a level for. The user fills a
 * prompt's arguments in a form; the text the software makes of them goes into the question's input field - the user
 * reads it and sends it, nothing is asked on its own.
 */
@Singleton
public class SoftwarePrompts {

    /** An argument's value at most - a value the user typed, not a document. */
    static final int MAX_ARGUMENT = 10_000;

    private final McpConnectionRepository connections;
    private final McpPromptRepository prompts;
    private final McpConnectionService connectionService;
    private final McpClient client;
    private final JsonMapper json;
    private final UserGroupService groups;

    public SoftwarePrompts(McpConnectionRepository connections, McpPromptRepository prompts,
                           McpConnectionService connectionService, McpClient client, JsonMapper json,
                           UserGroupService groups) {
        this.connections = connections;
        this.prompts = prompts;
        this.connectionService = connectionService;
        this.client = client;
        this.json = json;
        this.groups = groups;
    }

    /** What the user may use: turned on, still offered, of a connection its groups give it a level for (READ or WRITE). */
    public List<SoftwarePromptResponse> forUser(Long tenantId, Long userId) {
        List<SoftwarePromptResponse> usable = new ArrayList<>();
        for (McpConnectionRepository.AccessRow access : groups.accessOfUser(userId)) {
            McpConnection connection = connections.findByIdAndTenantId(access.connectionId(), tenantId).orElse(null);
            if (connection == null) {
                continue;
            }
            for (McpPrompt prompt : prompts.findByConnectionIdOrderByName(connection.id())) {
                if (prompt.enabled() && prompt.offered()) {
                    usable.add(new SoftwarePromptResponse(prompt.id(), connection.name(), prompt.name(), prompt.title(),
                            prompt.description(), McpPromptResponse.arguments(prompt.arguments(), json)));
                }
            }
        }
        return usable;
    }

    /**
     * The text the software makes of the prompt with the user's arguments, cut to what a question may have. 404 for a
     * prompt the user may not use - also one removed meanwhile (its connection deleted, given another address) -, 400
     * for a missing required argument or too long a value, 502 when the software fails. No transaction is held while
     * the software answers.
     */
    public String fill(Long tenantId, Long userId, Long promptId, @Nullable Map<String, String> arguments) {
        McpPrompt prompt = prompts.findById(promptId)
                .filter(candidate -> candidate.enabled() && candidate.offered())
                .orElseThrow(SoftwarePrompts::notFound);
        McpConnection connection = connections.findByIdAndTenantId(prompt.connectionId(), tenantId)
                .filter(candidate -> hasLevel(userId, candidate.id()))
                .orElseThrow(SoftwarePrompts::notFound);
        Map<String, Object> values = values(McpPromptResponse.arguments(prompt.arguments(), json),
                arguments == null ? Map.of() : arguments);
        String text = client.getPrompt(connection.tenantId(), connection.name(), connection.baseUrl(),
                connectionService.credential(connection), prompt.name(), values);
        return text.length() > ChatService.MAX_QUESTION_LENGTH ? text.substring(0, ChatService.MAX_QUESTION_LENGTH) : text;
    }

    /** Whether the user's groups give it a level (READ or WRITE) for the connection. */
    private boolean hasLevel(Long userId, Long connectionId) {
        return groups.accessOfUser(userId).stream().anyMatch(access -> access.connectionId().equals(connectionId));
    }

    private static ServiceException notFound() {
        return ServiceException.notFound("mcpPrompt.notFound", "Prompt not found");
    }

    /** The prompt's own arguments with the user's values - one the prompt does not have is not sent. */
    static Map<String, Object> values(List<McpPromptArgument> declared, Map<String, String> given) {
        Map<String, Object> values = new HashMap<>();
        for (McpPromptArgument argument : declared) {
            String value = given.get(argument.name());
            if (value == null || value.isBlank()) {
                if (argument.required()) {
                    throw ServiceException.badRequest("mcpPrompt.argumentRequired", "The value \"" + argument.name()
                            + "\" is required", Map.of("name", argument.name()));
                }
                continue;
            }
            if (value.length() > MAX_ARGUMENT) {
                throw ServiceException.badRequest("mcpPrompt.argumentTooLong", "A value may have at most "
                        + MAX_ARGUMENT + " characters", Map.of("max", String.valueOf(MAX_ARGUMENT)));
            }
            values.put(argument.name(), value.strip());
        }
        return values;
    }
}
