package de.alpdesk.services.filestore;

import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * What checking a store found.
 *
 * @param fingerprint the server's key or certificate as it presented itself - null when it was not reached
 * @param entries     the first names in the store's folder (status OK)
 */
public record CheckResult(Status status, @Nullable String fingerprint, List<String> entries) {

    public enum Status {
        /** Logged in, the folder read. */
        OK,
        /** No fingerprint confirmed yet - not logged in, so the password did not go to an unknown server. */
        UNCONFIRMED,
        /** The server presented another key or certificate than the one confirmed - not logged in. */
        CHANGED
    }

    public static CheckResult ok(String fingerprint, List<String> entries) {
        return new CheckResult(Status.OK, fingerprint, entries);
    }

    public static CheckResult unconfirmed(String fingerprint) {
        return new CheckResult(Status.UNCONFIRMED, fingerprint, List.of());
    }

    public static CheckResult changed(String fingerprint) {
        return new CheckResult(Status.CHANGED, fingerprint, List.of());
    }
}
