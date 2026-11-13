package de.alpdesk.services.mcp;

import de.alpdesk.services.branding.BrandingService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Nullable;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tenant's MCP server goes by the tenant's brand - the name of the look shown for the tenant, e.g. "Muster AI"
 * (docs/architecture/mcp.md "The brand"). The SDK serves one server for every tenant, its name, instructions and tool
 * descriptions fixed at start; so the handler Micronaut MCP's controller calls is wrapped here, and the answers to
 * initialize (server name, instructions) and tools/list (descriptions) get the brand of the MCP key's tenant. The
 * prompts - the tenant's chat templates ({@link AssistantMcpPrompts}) - differ per tenant: prompts/list and prompts/get
 * are answered here, and initialize says the server has prompts. The connected software an MCP key may use and that
 * passes through ({@link SoftwareMcpProxy}, roadmap point 41) differs per key: its tools join tools/list here, and their
 * calls are answered here - the SDK never sees them. So are the resources of the software that passes them on
 * ({@link SoftwareResources}) - the server has none of its own.
 */
@Singleton
public class BrandedMcpServer implements BeanCreatedEventListener<McpStatelessServerHandler> {

    private static final Logger LOG = LoggerFactory.getLogger(BrandedMcpServer.class);

    // A provider: a listener is created together with the bean it listens to, at start - the branding, its repository
    // and the data source are only needed with the first request.
    private final BeanProvider<BrandingService> brandings;
    private final BeanProvider<AssistantMcpPrompts> prompts;
    private final BeanProvider<SoftwareMcpProxy> software;
    private final BeanProvider<McpKeyService> keys;
    private final BeanProvider<SoftwareResources> resources;

    public BrandedMcpServer(BeanProvider<BrandingService> brandings, BeanProvider<AssistantMcpPrompts> prompts,
                            BeanProvider<SoftwareMcpProxy> software, BeanProvider<McpKeyService> keys,
                            BeanProvider<SoftwareResources> resources) {
        this.brandings = brandings;
        this.prompts = prompts;
        this.software = software;
        this.keys = keys;
        this.resources = resources;
    }

    @Override
    public McpStatelessServerHandler onCreated(BeanCreatedEvent<McpStatelessServerHandler> event) {
        McpStatelessServerHandler sdk = event.getBean();
        return new McpStatelessServerHandler() {
            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context, McpSchema.JSONRPCRequest request) {
                if (isPromptRequest(request)) {
                    // The templates are read from the database - off the event loop.
                    return Mono.fromCallable(() -> prompts(context, request)).subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(RuntimeException.class, e -> failed(request, e));
                }
                if (isResourceRequest(request)) {
                    // The software is asked over HTTP - off the event loop.
                    return Mono.fromCallable(() -> resources(context, request)).subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(RuntimeException.class, e -> failed(request, e));
                }
                if (isPassedThroughCall(request)) {
                    // The software is asked over HTTP, for up to app.ai.timeout - off the event loop.
                    return Mono.fromCallable(() -> passedThroughCall(context, request))
                            .subscribeOn(Schedulers.boundedElastic());
                }
                return sdk.handleRequest(context, request).flatMap(response -> branded(context, request, response));
            }

            @Override
            public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                return sdk.handleNotification(context, notification);
            }
        };
    }

    private Mono<McpSchema.JSONRPCResponse> branded(McpTransportContext context, McpSchema.JSONRPCRequest request,
                                                    McpSchema.JSONRPCResponse response) {
        if (!(response.result() instanceof McpSchema.InitializeResult)
                && !(response.result() instanceof McpSchema.ListToolsResult)) {
            return Mono.just(response);
        }
        // The brand and the key's software are read from the database - off the event loop.
        return Mono.fromCallable(() -> McpSchema.JSONRPCResponse.result(response.id(),
                        branded(context, response.result(), brand(context))))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(RuntimeException.class, e -> failed(request, e));
    }

    /**
     * A failure of ours while branding initialize or tools/list or listing the prompts (the database): logged by its
     * class and answered as a JSON-RPC error of our own words. Left to Micronaut MCP's controller, the exception's
     * text would go to the client - a database error's may carry a row's values - and nothing to our log.
     */
    private static Mono<McpSchema.JSONRPCResponse> failed(McpSchema.JSONRPCRequest request, RuntimeException e) {
        LOG.warn("The MCP server's {} failed: {}", request.method(), e.getClass().getSimpleName());
        return Mono.just(McpSchema.JSONRPCResponse.error(request.id(), new McpSchema.JSONRPCResponse.JSONRPCError(
                McpSchema.ErrorCodes.INTERNAL_ERROR, "The server could not answer - try again later", null)));
    }

    private static boolean isPromptRequest(McpSchema.JSONRPCRequest request) {
        return McpSchema.METHOD_PROMPT_LIST.equals(request.method()) || McpSchema.METHOD_PROMPT_GET.equals(request.method());
    }

    private static boolean isResourceRequest(McpSchema.JSONRPCRequest request) {
        return McpSchema.METHOD_RESOURCES_LIST.equals(request.method())
                || McpSchema.METHOD_RESOURCES_TEMPLATES_LIST.equals(request.method())
                || McpSchema.METHOD_RESOURCES_READ.equals(request.method());
    }

    /**
     * resources/list, resources/templates/list or resources/read of the key's software. A request we refuse - an
     * address that names none of its software, a cursor that is not ours - and the software's own error are JSON-RPC
     * errors: the protocol knows no other way to say "not found".
     */
    private McpSchema.JSONRPCResponse resources(McpTransportContext context, McpSchema.JSONRPCRequest request) {
        Long tenantId = AssistantMcpTools.tenantId(context);
        Long mcpKeyId = AssistantMcpTools.mcpKeyId(context);
        Map<?, ?> params = request.params() instanceof Map<?, ?> map ? map : Map.of();
        String cursor = params.get("cursor") instanceof String given ? given : null;
        try {
            Object result = switch (request.method()) {
                case McpSchema.METHOD_RESOURCES_LIST -> resources.get().list(tenantId, mcpKeyId, cursor);
                case McpSchema.METHOD_RESOURCES_TEMPLATES_LIST -> resources.get().templates(tenantId, mcpKeyId, cursor);
                default -> resources.get().read(tenantId, mcpKeyId,
                        params.get("uri") instanceof String uri ? uri : null);
            };
            return McpSchema.JSONRPCResponse.result(request.id(), result);
        } catch (SoftwareResources.Refused refused) {
            return McpSchema.JSONRPCResponse.error(request.id(), refused.error());
        }
    }

    private static boolean isPassedThroughCall(McpSchema.JSONRPCRequest request) {
        return McpSchema.METHOD_TOOLS_CALL.equals(request.method())
                && request.params() instanceof Map<?, ?> params
                && params.get("name") instanceof String name
                && SoftwareMcpProxy.isPassedThrough(name);
    }

    /**
     * A tools/call of a passed-through tool - whatever fails is a tool error, never a JSON-RPC error: the transport
     * sends those with HTTP 400 or 500, and the MCP SDK's client then ends the whole session, our own tools with it. A
     * name the key does not have (a client holding an old list: a connection renamed, a tool turned off) says to list
     * again; a failure of ours (a key no longer decryptable, the database) is logged and told without its details.
     */
    private McpSchema.JSONRPCResponse passedThroughCall(McpTransportContext context, McpSchema.JSONRPCRequest request) {
        Map<?, ?> params = (Map<?, ?>) request.params();
        String name = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> given ? (Map<String, Object>) given
                : Map.of();
        Long tenantId = AssistantMcpTools.tenantId(context);
        McpSchema.CallToolResult result;
        try {
            result = software.get().call(tenantId, AssistantMcpTools.mcpKeyId(context), name, arguments)
                    .orElseGet(() -> toolError("Unknown tool: " + name + " - it is not offered to this key (any more); "
                            + "list the tools again"));
        } catch (RuntimeException e) {
            // The exception's class only: a database error's text may carry a row's values (CLAUDE.md "Security").
            LOG.warn("The passed-through tool {} of tenant {} failed: {}", name, tenantId, e.getClass().getSimpleName());
            result = toolError("The tool " + name + " failed - try again later or answer without it");
        }
        return McpSchema.JSONRPCResponse.result(request.id(), result);
    }

    private static McpSchema.CallToolResult toolError(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    /** prompts/list, or prompts/get. */
    private McpSchema.JSONRPCResponse prompts(McpTransportContext context, McpSchema.JSONRPCRequest request) {
        Long tenantId = AssistantMcpTools.tenantId(context);
        Long mcpKeyId = AssistantMcpTools.mcpKeyId(context);
        if (McpSchema.METHOD_PROMPT_LIST.equals(request.method())) {
            McpSchema.ListPromptsResult own = prompts.get().list(tenantId, brand(context));
            List<McpSchema.Prompt> all = new ArrayList<>(own.prompts());
            all.addAll(software.get().prompts(tenantId, mcpKeyId));
            return McpSchema.JSONRPCResponse.result(request.id(), new McpSchema.ListPromptsResult(all, own.nextCursor()));
        }
        Map<?, ?> params = request.params() instanceof Map<?, ?> map ? map : Map.of();
        String name = params.get("name") instanceof String given ? given : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> given ? (Map<String, Object>) given : null;
        if (SoftwareMcpProxy.isPassedThrough(name)) {
            return passedThroughPrompt(request, tenantId, mcpKeyId, name, arguments);
        }
        return McpSchema.JSONRPCResponse.result(request.id(), templatePrompt(context, tenantId, name, arguments));
    }

    /** A template's prompt - a failure of ours (the database) is the prompt's text too, like a passed-through one's. */
    private McpSchema.GetPromptResult templatePrompt(McpTransportContext context, Long tenantId, String name,
                                                     @Nullable Map<String, Object> arguments) {
        try {
            return prompts.get().get(tenantId, brand(context), name, arguments);
        } catch (RuntimeException e) {
            LOG.warn("The prompt {} of tenant {} failed: {}", name, tenantId, e.getClass().getSimpleName());
            return promptFailed(name, "The prompt " + name + " could not be fetched - try again later");
        }
    }

    /**
     * A passed-through prompt's text. Whatever fails - an unknown name, a value we refuse, the software refusing or out
     * of reach, a failure of ours - comes back as the prompt's text saying so, not as a JSON-RPC error: that one ends
     * the MCP SDK client's whole session (decided 25 October 2026). The person who picked the prompt reads it.
     */
    private McpSchema.JSONRPCResponse passedThroughPrompt(McpSchema.JSONRPCRequest request, Long tenantId, Long mcpKeyId,
                                                          String name, @Nullable Map<String, Object> arguments) {
        McpSchema.GetPromptResult result;
        try {
            result = software.get().prompt(tenantId, mcpKeyId, name, arguments)
                    .orElseGet(() -> promptFailed(name, "Unknown prompt: " + name + " - it is not offered to this key "
                            + "(any more); list the prompts again"));
        } catch (ServiceException e) {
            result = promptFailed(name, "The prompt " + name + " could not be fetched: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("The passed-through prompt {} of tenant {} failed: {}", name, tenantId, e.getClass().getSimpleName());
            result = promptFailed(name, "The prompt " + name + " could not be fetched - try again later");
        }
        return McpSchema.JSONRPCResponse.result(request.id(), result);
    }

    private static McpSchema.GetPromptResult promptFailed(String name, String text) {
        return new McpSchema.GetPromptResult(name,
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }

    /**
     * The brand of the key's tenant, and the key's description with what its assistant offers where there is one -
     * first in the instructions, which a client shows as the server's summary (the general chat's topic chips).
     */
    private Object branded(McpTransportContext context, Object result, String brand) {
        Long keyId = AssistantMcpTools.mcpKeyId(context);
        return switch (result) {
            case McpSchema.InitializeResult initialize -> new McpSchema.InitializeResult(initialize.protocolVersion(),
                    withPrompts(initialize.capabilities(), resources.get().offered(AssistantMcpTools.tenantId(context),
                            keyId)), new McpSchema.Implementation(brand,
                    initialize.serverInfo().version()), AssistantMcpTools.knows(description(keyId), offer(keyId))
                    + AssistantMcpTools.instructions(brand) + software.get()
                    .instructions(AssistantMcpTools.tenantId(context), keyId),
                    initialize.meta());
            case McpSchema.ListToolsResult list -> new McpSchema.ListToolsResult(withPassedThrough(context,
                    AssistantMcpTools.branded(list.tools(), brand, description(keyId), offer(keyId))),
                    list.nextCursor(), list.meta());
            default -> result;
        };
    }

    private @Nullable String description(Long keyId) {
        return keys.get().descriptionOf(keyId).orElse(null);
    }

    private @Nullable String offer(Long keyId) {
        return keys.get().offerOf(keyId).orElse(null);
    }

    /** Our tools, then the key's passed-through software's. */
    private List<McpSchema.Tool> withPassedThrough(McpTransportContext context, List<McpSchema.Tool> own) {
        List<McpSchema.Tool> tools = new ArrayList<>(own);
        tools.addAll(software.get().tools(AssistantMcpTools.tenantId(context), AssistantMcpTools.mcpKeyId(context)));
        return tools;
    }

    /**
     * The SDK's capabilities with prompts - it has none registered, they are answered here - and resources where a
     * software of the key passes them on (no subscriptions, no list_changed: /mcp is stateless).
     */
    private static McpSchema.ServerCapabilities withPrompts(McpSchema.ServerCapabilities capabilities,
                                                            boolean resources) {
        return new McpSchema.ServerCapabilities(capabilities.completions(), capabilities.experimental(),
                capabilities.logging(), new McpSchema.ServerCapabilities.PromptCapabilities(false),
                resources ? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false)
                        : capabilities.resources(), capabilities.tools());
    }

    private String brand(McpTransportContext context) {
        return brandings.get().forTenant(AssistantMcpTools.tenantId(context)).name();
    }
}
