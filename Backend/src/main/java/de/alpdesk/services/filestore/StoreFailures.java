package de.alpdesk.services.filestore;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.http.HttpStatus;

import java.io.IOException;
import java.util.Map;

/** The failures opening, reading and writing a store shares between its types. */
final class StoreFailures {

    private StoreFailures() {
    }

    static ServiceException unconfirmed() {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.fingerprintUnconfirmed",
                "The server's fingerprint is not confirmed yet - a tenant admin checks the store and confirms it");
    }

    static ServiceException changed() {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.fingerprintChanged",
                "The server presented another key or certificate than the one confirmed - nothing was sent to it");
    }

    static ServiceException readFailed(String path, IOException e) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.readFailed",
                "Reading " + path + " failed: " + e.getMessage(), Map.of("path", path));
    }

    /** A listing the server answered only in part - a page that is no page, more announced without a way to ask. */
    static ServiceException listingBroken(String folder) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.readFailed",
                "Reading " + folder + " failed: the server's listing broke off", Map.of("path", folder));
    }

    static ServiceException writeFailed(String path, Exception e) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.writeFailed",
                "Writing " + path + " failed: " + e.getMessage(), Map.of("path", path));
    }

    /** The old file was removed, the new one could not take its place: what was written stays as the temporary file. */
    static ServiceException writeKept(String path, String temporary, String reason) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.writeFailed", "Writing " + path + " failed: "
                + reason + " - the new content is kept as " + temporary, Map.of("path", path));
    }

    static ServiceException tooManyEntries(String folder) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.tooManyEntries", "The folder " + folder
                + " has more than " + FileStoreSession.MAX_ENTRIES + " entries", Map.of("path", folder,
                "max", String.valueOf(FileStoreSession.MAX_ENTRIES)));
    }
}
