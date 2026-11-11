package de.alpdesk.controller.common;

import de.alpdesk.dto.common.ErrorResponse;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.json.JsonSyntaxException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.AuthenticationException;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.web.router.exceptions.UnsatisfiedRouteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.Set;

/**
 * Safety net for persistence-layer failures that a service didn't already pre-check for, e.g. a
 * uniqueness/foreign-key violation lost to a race between two concurrent requests. Without this,
 * such a failure bubbles up as a bare, unhandled 500 with no JSON body instead of a clean error
 * response the frontend can display.
 */
@Secured(SecurityRule.IS_ANONYMOUS)
@Controller
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Serialization failure, deadlock, lock not available (lock_timeout): the request lost a race, not its data. */
    private static final Set<String> TRANSIENT_SQL_STATES = Set.of("40001", "40P01", "55P03");
    /**
     * The database is briefly out of reach or out of resources, not the request wrong: SQL state classes 08 (connection
     * lost) and 53 (e.g. 53200, a full lock table), and 57P01-57P03 (PostgreSQL shutting
     * down or starting).
     */
    private static final Set<String> UNAVAILABLE_SQL_STATE_CLASSES = Set.of("08", "53");
    private static final Set<String> UNAVAILABLE_SQL_STATES = Set.of("57P01", "57P02", "57P03");
    private static final String UNIQUE_VIOLATION = "23505";
    /** NOT NULL, foreign key and CHECK violations. */
    private static final Set<String> INVALID_DATA_SQL_STATES = Set.of("23502", "23503", "23514");

    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = DataAccessException.class)
    public HttpResponse<ErrorResponse> handleDataAccessException(HttpRequest<?> request, DataAccessException exception) {
        String rootMessage = rootCauseMessage(exception);
        String lower = rootMessage == null ? "" : rootMessage.toLowerCase();
        // By SQL state: the message's wording depends on the database's lc_messages. The wording is the fallback for an
        // exception that carries no SQLException.
        String state = sqlState(exception);

        // The database broke a deadlock or a wait for another writer's lock ran past lock_timeout: nothing is wrong with
        // the request, and repeating it succeeds - 503 says "try again", a 500 would say "broken".
        if (noConnection(exception)) {
            LOG.warn("No database connection for {} {}: {}", request.getMethod(), request.getPath(), rootMessage);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("db.busy", "The server is busy - please try again."));
        }
        if (transientFailure(exception)) {
            LOG.warn("Transient database failure on {} {}: {}", request.getMethod(), request.getPath(), rootMessage);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("db.changedMeanwhile",
                            "The data is being changed by another request - please try again."));
        }
        if (databaseUnavailable(exception)) {
            LOG.warn("Database unavailable on {} {}: {}", request.getMethod(), request.getPath(), rootMessage);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("db.unavailable",
                            "The database is briefly unavailable - please try again."));
        }
        // A uniqueness violation genuinely means "this conflicts with something that already
        // exists" (e.g. two concurrent requests racing past an app-level pre-check) - 409 fits.
        if (UNIQUE_VIOLATION.equals(state) || state == null && lower.contains("unique constraint")) {
            LOG.warn("Uniqueness violation on {} {}: {}", request.getMethod(), request.getPath(), rootMessage);
            return HttpResponse.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("db.uniqueness",
                            "The action conflicts with an existing entry and could not be completed."));
        }
        // NOT NULL / CHECK / FOREIGN KEY violations mean the request itself was missing or
        // referencing invalid data, not that it conflicts with an existing entry - 400 fits
        // better than 409 here (e.g. a required field a controller didn't validate itself).
        // Set.of refuses contains(null) - an error without an SQL state would fail this handler itself.
        if (state != null && INVALID_DATA_SQL_STATES.contains(state) || state == null && (lower.contains("not null constraint")
                || lower.contains("not-null constraint") // PostgreSQL wording (both spellings)
                || lower.contains("check constraint") || lower.contains("foreign key constraint"))) {
            LOG.warn("Invalid data on {} {}: {}", request.getMethod(), request.getPath(), rootMessage);
            return HttpResponse.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("db.invalidData", "The request contains invalid or incomplete data."));
        }
        // Not the exception itself: its message and the stack trace's causes quote the failing row.
        LOG.error("Unhandled persistence error on {} {}: {} ({})", request.getMethod(), request.getPath(), rootMessage,
                exception.getClass().getName());
        return HttpResponse.<ErrorResponse>serverError()
                .body(ErrorResponse.of("error.unexpected", "An unexpected error occurred."));
    }

    /**
     * Without this, an {@link AuthorizationException} - thrown by Micronaut's own security filter
     * for both "no/invalid credentials" and "unknown route" (micronaut.security.reject-not-found
     * deliberately makes the two indistinguishable to an unauthenticated caller) - falls through to
     * the Throwable catch-all below instead of Micronaut's built-in security exception handling,
     * turning what should be a clean 401/403 into a generic 500. The frontend's auth interceptor
     * (Frontend/src/app/interceptors/auth.interceptor.ts) already keys off exactly these two status
     * codes to force a logout, so restoring them here also fixes session-expiry handling, not just
     * the status code.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = AuthorizationException.class)
    public HttpResponse<ErrorResponse> handleAuthorizationException(HttpRequest<?> request, AuthorizationException exception) {
        HttpStatus status = exception.isForbidden() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        LOG.debug("{} on {} {}", status, request.getMethod(), request.getPath());
        return HttpResponse.status(status)
                .body(status == HttpStatus.FORBIDDEN
                        ? ErrorResponse.of("auth.forbidden", "You are not allowed to perform this action.")
                        : ErrorResponse.of("auth.required", "Authentication is required."));
    }

    /**
     * Wrong username/password: Micronaut's login controller signals this by throwing an
     * {@link AuthenticationException}, which the Throwable catch-all below would otherwise turn
     * into a 500 ("An unexpected error occurred.") - both misleading for the operator and wrong
     * for any client keying off the status code. Deliberately does not say which of the two was
     * wrong.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = AuthenticationException.class)
    public HttpResponse<ErrorResponse> handleAuthenticationException(HttpRequest<?> request) {
        LOG.debug("Failed login attempt on {} {}", request.getMethod(), request.getPath());
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("auth.invalidCredentials", "Invalid username or password."));
    }

    /** The codes whose message carries a connected software's or an A2A agent's own words. */
    private static final Set<String> FOREIGN_TEXT = Set.of("mcpServer.error", "a2a.agentFailed");

    /**
     * A service operation that failed with a defined status (see {@link ServiceException}).
     * 5xx is logged as a warning - a connected software's or an A2A agent's error by its code only: its text may quote
     * what a user typed into one of its prompts or asked the agent, and the log keeps no prompt (privacy.md).
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = ServiceException.class)
    public HttpResponse<ErrorResponse> handleServiceException(HttpRequest<?> request, ServiceException exception) {
        if (exception.status().getCode() >= 500) {
            String what = FOREIGN_TEXT.contains(exception.code()) ? exception.code() : exception.getMessage();
            LOG.warn("Service operation failed on {} {}: {}", request.getMethod(), request.getPath(), what);
        }
        return HttpResponse.<ErrorResponse>status(exception.status()).body(
                new ErrorResponse(exception.getMessage(), exception.code(), exception.params()));
    }

    /**
     * Exceptions that already carry their HTTP status - most notably Micronaut's own
     * {@code NotFoundException} for an anonymous path nothing serves (e.g. a stale
     * {@code chunk-*.js} requested by a browser tab still running the previous frontend build).
     * Without this, the {@link Throwable} catch-all below turned every such 404 into a 500 with an
     * ERROR stack trace.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = HttpStatusException.class)
    public HttpResponse<ErrorResponse> handleHttpStatusException(HttpRequest<?> request, HttpStatusException exception) {
        HttpStatus status = exception.getStatus();
        if (status.getCode() >= 500) {
            LOG.error("{} on {} {}", status, request.getMethod(), request.getPath(), exception);
            return HttpResponse.<ErrorResponse>status(status)
                    .body(ErrorResponse.of("error.unexpected", "An unexpected error occurred."));
        }
        LOG.debug("{} on {} {}", status, request.getMethod(), request.getPath());
        ErrorResponse body = status == HttpStatus.NOT_FOUND ? ErrorResponse.of("request.notFound", "Not found.")
                : new ErrorResponse(status.getReason(), "request.refused",
                        Map.of("status", String.valueOf(status.getCode())));
        return HttpResponse.<ErrorResponse>status(status).body(body);
    }

    /**
     * A request Micronaut could not bind to the route's parameters - a missing required query
     * value/part/body, or a path variable that does not parse into its declared type
     * ({@code GET /admin/tenants/abc}). Micronaut maps these to 400 itself, but only as long as nothing
     * more general claims them first: the {@link Throwable} catch-all below is more general, so
     * without these three handlers every such request came back as a 500 "An unexpected error
     * occurred." - and, worse, logged a full stack trace at ERROR for what is a caller's typo,
     * which is exactly the noise that makes a log useless when a real fault does happen.
     * <p>
     * The offending parameter is named in the response because it is part of this API's published
     * contract (it is in the OpenAPI document at /swagger), unlike the exception's own message,
     * which can carry the rejected value and therefore stays out of the body.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = UnsatisfiedRouteException.class)
    public HttpResponse<ErrorResponse> handleUnsatisfiedRoute(HttpRequest<?> request, UnsatisfiedRouteException exception) {
        String name = exception.getArgument().getName();
        return badRequest(request, new ErrorResponse("Required parameter '" + name + "' is missing.",
                "request.parameterMissing", Map.of("name", name)));
    }

    /**
     * See {@link #handleUnsatisfiedRoute}: a parameter that is present but does not convert into
     * its declared type.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = ConversionErrorException.class)
    public HttpResponse<ErrorResponse> handleConversionError(HttpRequest<?> request, ConversionErrorException exception) {
        String name = exception.getArgument().getName();
        return badRequest(request, new ErrorResponse("Parameter '" + name + "' has an invalid value.",
                "request.parameterInvalid", Map.of("name", name)));
    }

    /**
     * See {@link #handleUnsatisfiedRoute}: a request body that is not well-formed JSON. The parser
     * message is deliberately not echoed - it quotes the offending input.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = JsonSyntaxException.class)
    public HttpResponse<ErrorResponse> handleJsonSyntax(HttpRequest<?> request) {
        return badRequest(request, ErrorResponse.of("request.unreadable", "The request body is not valid JSON."));
    }

    /**
     * See {@link #handleUnsatisfiedRoute}: an upload past {@code micronaut.server.multipart.max-file-size} or a request
     * past {@code micronaut.server.max-request-size}. Micronaut refuses it before a controller is called; without this
     * handler the catch-all made it a 500.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = ContentLengthExceededException.class)
    public HttpResponse<ErrorResponse> handleContentLengthExceeded(HttpRequest<?> request) {
        LOG.warn("Request too large on {} {}", request.getMethod(), request.getPath());
        return HttpResponse.<ErrorResponse>status(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                .body(ErrorResponse.of("request.tooLarge", "The upload exceeds the server's size limit."));
    }

    /**
     * A request target Java's URI parser refuses - scanners send {@code GET //}, and Caddy passes it through. Micronaut
     * hands the parse error to the error routes with the path "/" in place of the target; without this handler the
     * catch-all made it a 500 with an ERROR stack trace. Logged at DEBUG like a scanner's 404
     * ({@link #handleHttpStatusException}): internet noise, not a warning.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true, exception = URISyntaxException.class)
    public HttpResponse<ErrorResponse> handleInvalidUri(HttpRequest<?> request, URISyntaxException exception) {
        // The request's path is "/" here: the parser's message names the target that was sent.
        LOG.debug("Bad request on {}: invalid URI ({})", request.getMethod(), exception.getMessage());
        return HttpResponse.<ErrorResponse>status(HttpStatus.BAD_REQUEST).body(
                ErrorResponse.of("request.uriInvalid", "The request URI is invalid."));
    }

    /**
     * A caller's mistake, not a fault of this server: logged at WARN without a stack trace, so the
     * ERROR level keeps meaning "something here is broken".
     */
    private static HttpResponse<ErrorResponse> badRequest(HttpRequest<?> request, ErrorResponse error) {
        LOG.warn("Bad request on {} {}: {}", request.getMethod(), request.getPath(), error.message());
        return HttpResponse.<ErrorResponse>status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Catch-all for anything that isn't a {@link DataAccessException}, {@link AuthenticationException},
     * {@link AuthorizationException}, {@link HttpStatusException}, {@link ServiceException}
     * or one of the caller's mistakes above (e.g. an NPE from an unguarded optional-FK dereference, or a bug in request
     * handling) - without this, such a failure bypasses the sanitizing above entirely and falls through to Micronaut's own
     * default error handler, whose verbosity depends on the active environment.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Error(global = true)
    public HttpResponse<ErrorResponse> handleUnexpectedException(HttpRequest<?> request, Throwable exception) {
        // Every pooled connection stayed busy past the pool's connection timeout (the driver's exception comes wrapped
        // in the transaction manager's, not in a DataAccessException): the server is busy, repeating the request succeeds.
        if (noConnection(exception)) {
            LOG.warn("No database connection for {} {}: {}", request.getMethod(), request.getPath(), rootCauseMessage(exception));
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("db.busy", "The server is busy - please try again."));
        }
        // A tenant database's repositories (plain JDBC) wrap a lock timeout or a deadlock in an IllegalStateException.
        if (transientFailure(exception)) {
            LOG.warn("Transient database failure on {} {}: {}", request.getMethod(), request.getPath(), rootCauseMessage(exception));
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("db.changedMeanwhile",
                            "The data is being changed by another request - please try again."));
        }
        // A connection lost while opening or committing a transaction comes wrapped in the transaction manager's exception.
        if (databaseUnavailable(exception)) {
            LOG.warn("Database unavailable on {} {}: {}", request.getMethod(), request.getPath(), rootCauseMessage(exception));
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("db.unavailable",
                            "The database is briefly unavailable - please try again."));
        }
        LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getPath(), exception);
        return HttpResponse.<ErrorResponse>serverError()
                .body(ErrorResponse.of("error.unexpected", "An unexpected error occurred."));
    }

    static boolean transientFailure(Throwable throwable) {
        String state = sqlState(throwable);
        return state != null && TRANSIENT_SQL_STATES.contains(state);
    }

    static boolean databaseUnavailable(Throwable throwable) {
        String state = sqlState(throwable);
        return state != null && (UNAVAILABLE_SQL_STATES.contains(state)
                || state.length() == 5 && UNAVAILABLE_SQL_STATE_CLASSES.contains(state.substring(0, 2)));
    }

    /** The SQL state of the first SQLException in the cause chain that has one - null if none has. */
    static String sqlState(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    static boolean noConnection(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The database's message without the rows it quotes: PostgreSQL adds "Detail: Failing row contains
     * (...)" / "Key (...)=(...)" with the row's values - personal data must not reach the log.
     */
    static String rootCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null) {
            return null;
        }
        int detail = message.indexOf("Detail:");
        return (detail >= 0 ? message.substring(0, detail) : message).strip();
    }
}
