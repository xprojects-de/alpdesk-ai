package de.alpdesk.services.filestore;

import java.net.InetAddress;

/**
 * How the app speaks to one type of store (SftpStoreClient, FtpsStoreClient). Failures are ServiceExceptions with a
 * fileStore.* code and 502 - the server is out of reach, refuses the login, has no such folder.
 */
public interface FileStoreClient {

    /** Names of the folder's entries listed at most by a check. */
    int CHECK_ENTRIES = 50;

    /**
     * Connects to the server at the address resolved and allowed before, checks its key or certificate against the
     * confirmed fingerprint - logging in only when it matches - and lists the store's folder.
     */
    CheckResult check(InetAddress address, ServerSettings settings, String password);

    /**
     * Connects and logs in to read files - only when the server's key or certificate is the confirmed one (or, FTPS,
     * one an authority the installation trusts vouches for): fileStore.fingerprintUnconfirmed or
     * fileStore.fingerprintChanged otherwise, nothing sent.
     */
    FileStoreSession open(InetAddress address, ServerSettings settings, String password);
}
