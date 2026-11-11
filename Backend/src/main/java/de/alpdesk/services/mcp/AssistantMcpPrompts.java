package de.alpdesk.services.mcp;

import de.alpdesk.entities.assistant.ChatTemplate;
import de.alpdesk.services.assistant.AssistantService;
import io.micronaut.core.annotation.Nullable;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tenant's chat templates as MCP prompts (docs/architecture/mcp.md "Templates as prompts"): clients offer a prompt
 * as a slash command - Copilot /mcp.muster-ai.code-review, Claude Code /mcp__muster-ai__code-review -, and the prompt
 * tells the client's model to ask the assistant with that template. Each tenant has templates of its own, so the
 * prompts are answered per tenant ({@link BrandedMcpServer}), never registered with the SDK at start.
 */
@Singleton
public class AssistantMcpPrompts {

    /** The longest prompt name - the client puts it after the server's name in a slash command. */
    static final int MAX_NAME = 40;
    static final String QUESTION = "question";

    private final AssistantService assistants;

    public AssistantMcpPrompts(AssistantService assistants) {
        this.assistants = assistants;
    }

    public McpSchema.ListPromptsResult list(Long tenantId, String brand) {
        return new McpSchema.ListPromptsResult(byName(tenantId).entrySet().stream()
                .map(entry -> prompt(entry.getKey(), entry.getValue(), brand))
                .toList(), null);
    }

    /**
     * The prompt of this name with the question it was given. A name the tenant has no template for - one deleted since
     * the client listed the prompts - asks without a template: an error would end the session of some clients (the
     * MCP transport answers it with HTTP 400, which the Python SDK takes for a broken connection).
     */
    public McpSchema.GetPromptResult get(Long tenantId, String brand, String name, @Nullable Map<String, Object> arguments) {
        ChatTemplate template = byName(tenantId).get(name);
        Object question = arguments == null ? null : arguments.get(QUESTION);
        String text = "Ask " + brand + " with the tool " + AssistantMcpTools.ASK_ASSISTANT
                + (template == null ? "" : " and the template \"" + template.name() + "\"") + ". The question: "
                + (question instanceof String given && !given.isBlank() ? given.strip()
                : "what I am working on right now - give the full context, and send the files it is about in files.");
        String description = template == null ? "Asks " + brand + " (the company's assistant)" : description(template, brand);
        return new McpSchema.GetPromptResult(description,
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }

    private static McpSchema.Prompt prompt(String name, ChatTemplate template, String brand) {
        McpSchema.PromptArgument question = new McpSchema.PromptArgument(QUESTION, "Question",
                "What to ask - the files it is about go with the question", false);
        return new McpSchema.Prompt(name, template.name(), description(template, brand), List.of(question));
    }

    private static String description(ChatTemplate template, String brand) {
        return "Asks " + brand + " (the company's assistant) with its template \"" + template.name() + "\"";
    }

    /**
     * The templates by their prompt names, in the order the tenant sees them. A name taken twice stays the oldest
     * template's; the others get their id ("code-review-17"), not a number by position: a template added later must not
     * move a slash command to another one.
     */
    private Map<String, ChatTemplate> byName(Long tenantId) {
        List<ChatTemplate> all = assistants.templates(tenantId);
        Map<String, Long> oldest = new HashMap<>();
        for (ChatTemplate template : all) {
            oldest.merge(promptName(template.name()), template.id(), Math::min);
        }
        Map<String, ChatTemplate> templates = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>(oldest.keySet());
        for (ChatTemplate template : all) {
            String name = promptName(template.name());
            if (!template.id().equals(oldest.get(name))) {
                String suffix = "-" + template.id();
                name = name.substring(0, Math.min(name.length(), MAX_NAME - suffix.length())) + suffix;
                taken.add(name);
            }
            templates.put(name, template);
        }
        return templates;
    }

    /**
     * A template's name the way a slash command takes it ("Code-Review" → "code-review", "Prüfung" → "pruefung") - the
     * rule the frontend names the server by (utils/mcp.util.ts mcpServerName).
     */
    static String promptName(String templateName) {
        String name = templateName.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        name = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-");
        name = name.substring(0, Math.min(name.length(), MAX_NAME)).replaceAll("^-+|-+$", "");
        return name.isEmpty() ? "template" : name;
    }
}
