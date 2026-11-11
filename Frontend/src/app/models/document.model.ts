export type DocumentStatus = "PENDING" | "PROCESSING" | "READY" | "FAILED";

/** The translation key of each state's name. */
export const DOCUMENT_STATUS_LABELS: Record<DocumentStatus, string> = {
    PENDING: "documents.status.pending",
    PROCESSING: "documents.status.processing",
    READY: "documents.status.ready",
    FAILED: "documents.status.failed",
};

/**
 * Where a document comes from: uploaded in the frontend, pushed by the client software, or a file store's folder synced
 * by a knowledge source.
 */
export type DocumentOrigin = "UPLOAD" | "API" | "SOURCE";

/** The translation key of each origin's name. */
export const DOCUMENT_ORIGIN_LABELS: Record<DocumentOrigin, string> = {
    UPLOAD: "documents.origin.upload",
    API: "documents.origin.api",
    SOURCE: "documents.origin.source",
};

/** A tenant's document the chat answers from (named so it does not clash with the DOM's Document). */
export interface TenantDocument {
    id: number;
    fileName: string;
    /** The folder it came from in an imported ZIP archive ("Handbuch/Kapitel"); "" otherwise. */
    folder: string;
    contentType: string;
    sizeBytes: number;
    status: DocumentStatus;
    /** Why embedding failed (status FAILED). */
    error: string | null;
    chunkCount: number;
    origin: DocumentOrigin;
    /** The client software's own key - a push with the same key replaces this document. */
    externalId: string | null;
    /** The knowledge area it belongs to; null: the company knowledge everyone finds. */
    areaId: number | null;
    /** The area's name; null for the company knowledge. */
    area: string | null;
    /** The knowledge source keeping it up to date (origin SOURCE); null for others. */
    sourceId: number | null;
    createdAt: string;
    updatedAt: string;
}

/** What importing a file of a ZIP archive did. */
export type ImportOutcome = "CREATED" | "REPLACED" | "UNCHANGED" | "DUPLICATE" | "SKIPPED";

/** The translation key of each outcome's name. */
export const IMPORT_OUTCOME_LABELS: Record<ImportOutcome, string> = {
    CREATED: "documents.importOutcome.created",
    REPLACED: "documents.importOutcome.replaced",
    UNCHANGED: "documents.importOutcome.unchanged",
    DUPLICATE: "documents.importOutcome.duplicate",
    SKIPPED: "documents.importOutcome.skipped",
};

/** A file of an imported archive: DUPLICATE names the document with the same content, SKIPPED why (detail). */
export interface ImportedFile {
    path: string;
    outcome: ImportOutcome;
    documentId: number | null;
    detail: string | null;
}
