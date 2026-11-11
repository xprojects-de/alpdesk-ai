package de.alpdesk.entities.chat;

public enum FileOrigin {
    /** Uploaded by the user or the client software. */
    UPLOADED,
    /** Created by the model (tool create_file) for download. */
    GENERATED
}
