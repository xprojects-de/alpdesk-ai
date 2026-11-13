package de.alpdesk.services.a2a;

import de.alpdesk.entities.assistant.ChatTemplate;
import de.alpdesk.entities.task.Task;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.branding.BrandingService;
import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.services.mcp.McpKeyService;
import de.alpdesk.services.task.TaskService;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The cards of the tenants' assistant as an A2A agent (docs/architecture/a2a.md "The agent cards"): a neutral public
 * card - no tenant, no brand: nobody learns by asking which companies use the installation - and the extended card of
 * the asking key, with the tenant's brand, the key's description and its templates and tasks as skills. Both declare
 * our two extensions, never required, their description served by the installation itself.
 */
@Singleton
public class A2aCards {

    /** metadata.template, metadata.task, the artifacts report and findings. */
    public static final String ASSISTANT_EXTENSION = "urn:alpdesk-ai:a2a:assistant:1";
    static final String SECURITY_SCHEME = "key";

    private final BrandingService brandings;
    private final McpKeyService keys;
    private final AssistantService assistants;
    private final TaskService tasks;
    private final McpConnectionService connections;
    private final String version;
    private final String contextPath;

    public A2aCards(BrandingService brandings, McpKeyService keys, AssistantService assistants, TaskService tasks,
                    McpConnectionService connections,
                    @Value("${app.version}") String version,
                    @Value("${micronaut.server.context-path:}") @Nullable String contextPath) {
        this.brandings = brandings;
        this.keys = keys;
        this.assistants = assistants;
        this.tasks = tasks;
        this.connections = connections;
        this.version = version;
        this.contextPath = contextPath == null ? "" : contextPath.strip().replaceAll("/+$", "");
    }

    /** The anonymous card at /.well-known/agent-card.json, for a request from this origin (scheme and host). */
    public AgentCard publicCard(String origin) {
        return card(origin, "AI assistant", "The AI assistant of a company on this installation - authenticate with "
                + "the company's key to see what it knows", new AgentProvider("Zirmo", origin), List.of());
    }

    /** The card of the asking key: the tenant's brand, the key's description, the answer, its templates and tasks. */
    public AgentCard extendedCard(Long tenantId, Long mcpKeyId, String origin) {
        String brand = brandings.forTenant(tenantId).name();
        String description = keys.descriptionOf(mcpKeyId).orElse(brand + " is the AI assistant of the company: it "
                + "knows the company's own documents, rules and ways of working")
                + keys.offerOf(mcpKeyId).map(offer -> "\n\n" + offer).orElse("");
        List<AgentSkill> skills = new ArrayList<>();
        skills.add(AgentSkill.builder().id("answer").name("Answer")
                .description("Answers a question from the company's knowledge and its connected software, naming the "
                        + "documents it drew on")
                .tags(List.of("knowledge", "documents")).build());
        // A tenant answering through its agent takes no template (ChatService.prepareForAgent): none is listed.
        List<ChatTemplate> templates = connections.answeringAgent(tenantId).isPresent() ? List.of()
                : assistants.templates(tenantId);
        for (ChatTemplate template : templates) {
            skills.add(AgentSkill.builder().id("template:" + template.id()).name(template.name())
                    .description(described(template.description()) + "A template of " + brand + " - ask with metadata[\""
                            + ASSISTANT_EXTENSION + "\"].template = \"" + template.name() + "\"")
                    .tags(List.of("template")).build());
        }
        for (Task task : tasks.tasksOfMcpKey(tenantId, mcpKeyId)) {
            skills.add(AgentSkill.builder().id("task:" + task.id()).name(task.name())
                    .description(described(tasks.description(task)) + "A scheduled task of " + brand
                            + " - start it with metadata[\"" + ASSISTANT_EXTENSION
                            + "\"].task = \"" + task.name() + "\": the answer is a task whose artifacts are its "
                            + "report and its findings")
                    .tags(List.of("task")).build());
        }
        return card(origin, brand, description, new AgentProvider(brand, origin), skills);
    }

    /** What a tenant admin wrote it is for, ahead of how to ask for it; empty without. */
    private static String described(@Nullable String description) {
        return description == null ? "" : description + "\n\n";
    }

    /** The absolute address under the context path. */
    String address(String origin, String path) {
        return origin + contextPath + path;
    }

    private AgentCard card(String origin, String name, String description, AgentProvider provider,
                           List<AgentSkill> skills) {
        return AgentCard.builder()
                .name(name)
                .description(description)
                .provider(provider)
                .version(version)
                .capabilities(new AgentCapabilities(true, true, true, extensions(origin)))
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(skills)
                .securitySchemes(Map.of(SECURITY_SCHEME, new HTTPAuthSecurityScheme("mcp_... key of the company",
                        "Bearer", null)))
                .securityRequirements(List.of(new SecurityRequirement(Map.of(SECURITY_SCHEME, List.of()))))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", address(origin, "/a2a"), null, "1.0")))
                .build();
    }

    private List<AgentExtension> extensions(String origin) {
        return List.of(
                new AgentExtension("Our assistant's conventions: metadata.template (a template's name, listed as a "
                        + "skill) answers with that template; metadata.task (a scheduled task's name, listed as a skill) "
                        + "starts that task and answers with a Task whose artifacts are \"report\" (text) and "
                        + "\"findings\" (data).", Map.of("documentation", address(origin, "/a2a/extensions/assistant/1")),
                        false, ASSISTANT_EXTENSION),
                new AgentExtension("Who asks, as our client sends it to an agent: the company, the language, the user "
                        + "or key, its groups and the skills it may use - in message.metadata under this URI.",
                        Map.of("documentation", address(origin, "/a2a/extensions/asker/1")), false,
                        A2aClient.ASKER_EXTENSION));
    }
}
