package de.alpdesk.security;

import de.alpdesk.services.tenant.ApiKeyService;
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
 * Accepts tenant API keys as bearer tokens ({@code Authorization: Bearer ai_...}) next to the
 * admin's JWTs. Micronaut asks every {@link TokenValidator} in turn and takes the first
 * authentication: the JWT validator yields nothing for a {@code ai_} key, and this one yields
 * nothing for anything that is not an {@code ai_} key.
 * <p>
 * The resulting authentication carries {@link Roles#TENANT} and the tenant's id as the
 * {@link Roles#TENANT_ID_ATTRIBUTE} attribute - the only source of the tenant id for tenant
 * endpoints - and the key's id ({@link Roles#API_KEY_ID_ATTRIBUTE}), which decides its knowledge areas.
 */
@Singleton
public class ApiKeyTokenValidator implements TokenValidator<HttpRequest<?>> {

    private final ApiKeyService apiKeyService;
    private final Scheduler blocking;

    public ApiKeyTokenValidator(ApiKeyService apiKeyService, @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.apiKeyService = apiKeyService;
        this.blocking = Schedulers.fromExecutorService(executor);
    }

    @NonNull
    @Override
    public Publisher<Authentication> validateToken(@NonNull String token, HttpRequest<?> request) {
        if (!token.startsWith(ApiKeyService.KEY_PREFIX)) {
            return Mono.empty();
        }
        // A blocking JDBC call - on the blocking executor, not Reactor's boundedElastic: the MCP server's tools run
        // there for minutes and would hold every request's sign-in.
        return Mono.fromCallable(() -> apiKeyService.authenticate(token)
                        .map(caller -> Authentication.build("tenant:" + caller.tenantId(), List.of(Roles.TENANT),
                                Map.of(Roles.TENANT_ID_ATTRIBUTE, caller.tenantId(),
                                        Roles.API_KEY_ID_ATTRIBUTE, caller.apiKeyId(),
                                        Roles.KEEPS_HISTORY_ATTRIBUTE, caller.keepsHistory())))
                        .orElse(null))
                .subscribeOn(blocking);
    }
}
