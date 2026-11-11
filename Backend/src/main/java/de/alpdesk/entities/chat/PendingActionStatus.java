package de.alpdesk.entities.chat;

/** Where a changing tool call of a connected software stands: waiting for the user, or decided. */
public enum PendingActionStatus {
    PENDING,
    RUN,
    CANCELLED,
    /** A day passed, or a new question came - the call no longer fits the conversation. */
    EXPIRED
}
