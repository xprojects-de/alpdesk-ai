package de.alpdesk.services.filestore;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * What an SFTP or FTPS store needs that is no secret - stored as JSON in file_store.settings.
 *
 * @param fingerprint the server's key (SFTP) or certificate (FTPS) a tenant admin confirmed, "SHA256:" and Base64;
 *                    null: none confirmed - an SFTP store is then not logged in to, nor an FTPS store whose certificate
 *                    no authority the installation trusts vouches for
 */
@Serdeable
public record ServerSettings(String host, int port, String username, String basePath, @Nullable String fingerprint) {

    public ServerSettings withFingerprint(@Nullable String newFingerprint) {
        return new ServerSettings(host, port, username, basePath, newFingerprint);
    }

    /** Another server than before: its fingerprint must be confirmed anew, and the password is not sent to it unasked. */
    public boolean sameServer(ServerSettings other) {
        return host.equals(other.host) && port == other.port;
    }

    /** The same files as before: the same server and folder - another user name is taken to see the same ones. */
    public boolean sameFiles(ServerSettings other) {
        return sameServer(other) && basePath.equals(other.basePath);
    }
}
