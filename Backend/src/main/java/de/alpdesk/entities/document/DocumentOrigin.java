package de.alpdesk.entities.document;

/** Where a document comes from. */
public enum DocumentOrigin {
    /** Uploaded in the frontend by the admin or a tenant admin. */
    UPLOAD,
    /** Pushed by the client software (/api/v1/documents), possibly with its own key (externalId). */
    API,
    /** A file of a knowledge source's folder, kept up to date by the source (docs/architecture/knowledge-sources.md). */
    SOURCE
}
