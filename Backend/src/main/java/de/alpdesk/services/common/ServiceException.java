package de.alpdesk.services.common;

import io.micronaut.http.HttpStatus;

import java.util.Map;

/**
 * A failed service operation with the HTTP status the caller should get - thrown by services and
 * turned into an ErrorResponse by the GlobalExceptionHandler, so admin and tenant controllers share
 * one error behaviour without per-method try/catch. The message is English and shown to the caller.
 * <p>
 * Every exception carries a code next to the message: {@code <area>.<rule>} in lowerCamelCase, e.g.
 * {@code user.usernameTaken} - stable, so the frontend translates it and the client software may switch on it. The
 * params are the values inserted into the message ({@code username}), for the translation to insert them too. The
 * same rule shares one code wherever the message is the same; a new code needs an entry in every language file of
 * the frontend ({@code errors.backend.<code>}).
 * <p>
 * Status conventions: 400 invalid input, 404 unknown or outside the tenant's scope (never "exists but not yours"),
 * 409 conflict - also for a refused action, never 403: the frontend takes a 403 for a session that ended and logs
 * out -, 413 payload too large, 503 another request changed the data meanwhile - repeating it succeeds.
 */
public class ServiceException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> params;

    public ServiceException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ServiceException(HttpStatus status, String code, String message, Map<String, String> params) {
        super(message);
        this.status = status;
        this.code = code;
        this.params = Map.copyOf(params);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> params() {
        return params;
    }

    public static ServiceException notFound(String code, String message) {
        return new ServiceException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ServiceException notFound(String code, String message, Map<String, String> params) {
        return new ServiceException(HttpStatus.NOT_FOUND, code, message, params);
    }

    public static ServiceException badRequest(String code, String message) {
        return new ServiceException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ServiceException badRequest(String code, String message, Map<String, String> params) {
        return new ServiceException(HttpStatus.BAD_REQUEST, code, message, params);
    }

    public static ServiceException conflict(String code, String message) {
        return new ServiceException(HttpStatus.CONFLICT, code, message);
    }

    public static ServiceException conflict(String code, String message, Map<String, String> params) {
        return new ServiceException(HttpStatus.CONFLICT, code, message, params);
    }

    public static ServiceException unavailable(String code, String message) {
        return new ServiceException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    public static ServiceException unavailable(String code, String message, Map<String, String> params) {
        return new ServiceException(HttpStatus.SERVICE_UNAVAILABLE, code, message, params);
    }
}
