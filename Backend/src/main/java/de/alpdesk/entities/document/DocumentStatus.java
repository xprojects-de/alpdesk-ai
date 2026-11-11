package de.alpdesk.entities.document;

public enum DocumentStatus {
    /** Uploaded, waiting to be embedded. */
    PENDING,
    PROCESSING,
    /** Embedded - the chat finds it. */
    READY,
    /** Embedding failed - see the document's error. */
    FAILED
}
