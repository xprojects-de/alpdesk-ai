package de.alpdesk.security;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;

/**
 * The origin a browser or client reached us at (scheme and host) - the A2A cards' addresses and a sign-in's way back
 * are built from it. Behind a proxy that ends TLS (Caddy, nginx) the app is spoken to in plain HTTP: the scheme is the
 * one the proxy's X-Forwarded-Proto names.
 */
public final class RequestOrigin {

    public static String of(HttpRequest<?> request) {
        String forwarded = request.getHeaders().get("X-Forwarded-Proto");
        boolean https = forwarded != null && !forwarded.isBlank()
                ? "https".equalsIgnoreCase(forwarded.split(",")[0].strip())
                : request.isSecure();
        return (https ? "https://" : "http://") + request.getHeaders().get(HttpHeaders.HOST);
    }

    private RequestOrigin() {
    }
}
