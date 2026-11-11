package de.alpdesk.entities.filestore;

/** How a file store is reached. A new type is one more case here, in the migration's check and in FileStoreService. */
public enum FileStoreType {
    /** File transfer over SSH - the server proves itself with its host key. */
    SFTP,
    /** FTP over TLS (explicit, AUTH TLS) - the server proves itself with its certificate. Plain FTP is refused. */
    FTPS,
    /** A bucket of Amazon S3 or of a compatible store, read with an access key - the server proves itself over HTTPS. */
    S3,
    /**
     * A document library of a SharePoint site or a OneDrive (a SharePoint site of its own), read through Microsoft Graph
     * by an app registration of the customer's Entra ID.
     */
    SHAREPOINT,
    /**
     * A mailbox folder, read through IMAP - read only: its messages are folders, their attachments the files (documents
     * mailed to an address of the company's own).
     */
    IMAP;

    /** Whether the server is confirmed by the fingerprint of its key or certificate (SFTP, FTPS). */
    public boolean hasFingerprint() {
        return this == SFTP || this == FTPS;
    }
}
