package de.alpdesk.services.mcp;

import io.micronaut.core.annotation.Nullable;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * How a call to a connected software signs in (docs/architecture/mcp.md): the bearer sent with each request - none,
 * the connection's fixed key, or the access token of its OAuth sign-in - and, for a sign-in, how to renew the token
 * once after a 401 (the token that failed in, the new one out) and what to do when the renewed one is refused too
 * (the sign-in is gone: someone must sign in again).
 */
public record Credential(@Nullable Supplier<String> token, @Nullable UnaryOperator<String> renew,
                         @Nullable Runnable signInLost) {

    public static final Credential NONE = new Credential(null, null, null);

    public static Credential key(@Nullable String key) {
        return key == null ? NONE : new Credential(() -> key, null, null);
    }

    public boolean signedIn() {
        return renew != null;
    }
}
