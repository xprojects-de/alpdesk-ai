package de.alpdesk.security;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.context.annotation.Value;
import de.alpdesk.services.mcp.McpKeyService;
import io.micronaut.http.HttpRequest;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.validator.TokenValidator;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.Map;

/**
 * Accepts the tenants' MCP keys as bearer tokens ({@code Authorization: Bearer mcp_...}), next to the JWTs and the API
 * keys - each validator yields nothing for the others' tokens. The authentication carries {@link Roles#MCP} only, so
 * such a key reaches /mcp and /a2a and nothing else, the tenant's id as {@link Roles#TENANT_ID_ATTRIBUTE} and the key's as
 * {@link Roles#MCP_KEY_ID_ATTRIBUTE} - a tenant has several, each with its own areas and web search. A key opens only
 * the doors chosen for it (docs/architecture/a2a.md "The doors of a key"): at another the request has no
 * authentication, a 401 like an unknown key.
 */
@Singleton
public class McpKeyTokenValidator implements TokenValidator<HttpRequest<?>> {

    private final McpKeyService keys;
    private final Scheduler blocking;
    private final String a2aPath;

    public McpKeyTokenValidator(McpKeyService keys, @Named(TaskExecutors.BLOCKING) ExecutorService executor,
                                @Value("${micronaut.server.context-path:}") @Nullable String contextPath) {
        this.keys = keys;
        this.blocking = Schedulers.fromExecutorService(executor);
        this.a2aPath = a2aPath(contextPath);
    }

    /** "/a2a" below the context path - written with or without its slashes. */
    static String a2aPath(@Nullable String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip().replaceAll("/+$", "");
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return path + "/a2a";
    }

    /** The door the request knocks at: /a2a needs a key with a2a, everything else (/mcp) one with mcp. */
    private boolean opens(McpKeyService.Caller caller, HttpRequest<?> request) {
        String path = request == null ? "" : request.getPath();
        boolean a2aDoor = path.equals(a2aPath) || path.startsWith(a2aPath + "/");
        return a2aDoor ? caller.a2a() : caller.mcp();
    }

    @NonNull
    @Override
    public Publisher<Authentication> validateToken(@NonNull String token, HttpRequest<?> request) {
        if (!token.startsWith(McpKeyService.KEY_PREFIX)) {
            return Mono.empty();
        }
        // A blocking JDBC call - on the blocking executor, not Reactor's boundedElastic: the MCP server's tools run
        // there for minutes and would hold every request's sign-in.
        return Mono.fromCallable(() -> keys.authenticate(token)
                        .filter(caller -> opens(caller, request))
                        .map(caller -> Authentication.build("mcp:" + caller.tenantId() + ":" + caller.mcpKeyId(),
                                List.of(Roles.MCP), Map.of(Roles.TENANT_ID_ATTRIBUTE, caller.tenantId(),
                                        Roles.MCP_KEY_ID_ATTRIBUTE, caller.mcpKeyId())))
                        .orElse(null))
                .subscribeOn(blocking);
    }
}
