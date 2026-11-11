import {
    DOCUMENT_STATUS_LABELS,
    DocumentStatus,
    ImportedFile,
    ImportOutcome,
    TenantDocument,
} from "../models/document.model";

/** Like the backend's limit of one document. */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
/** Like the backend's request size limit - a ZIP archive of documents. */
export const MAX_ARCHIVE_BYTES = 512 * 1024 * 1024;

/** "1.5 MB" ("1,5 MB" in German) - decimal places only below 10 of a unit; without a locale the browser's. */
export function formatBytes(bytes: number, locale?: string): string {
    const units = ["B", "KB", "MB", "GB"];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit++;
    }
    const digits = unit === 0 || value >= 10 ? 0 : 1;
    return `${value.toLocaleString(locale, {maximumFractionDigits: digits})} ${units[unit]}`;
}

/** Documents still being embedded - while there are any, the list is reloaded regularly. */
export function hasPendingDocuments(documents: TenantDocument[]): boolean {
    return documents.some(document => document.status === "PENDING" || document.status === "PROCESSING");
}

/** How long a document may wait or be embedded before "Process again" is offered for it, too - a scan takes minutes. */
export const STUCK_AFTER_MS = 30 * 60 * 1000;

/**
 * Whether "Process again" is offered: for a failed document, and for one waiting or being embedded for longer than
 * {@link STUCK_AFTER_MS} - its run may have broken off (the database out of reach for a moment), which otherwise only a
 * restart of the server takes up again. Not earlier: a document still in progress would be embedded twice.
 */
export function canRetry(document: Pick<TenantDocument, "status" | "updatedAt">, now = Date.now()): boolean {
    if (document.status === "FAILED") {
        return true;
    }
    if (document.status !== "PENDING" && document.status !== "PROCESSING") {
        return false;
    }
    return now - Date.parse(document.updatedAt) > STUCK_AFTER_MS;
}

/** The selected files that may be uploaded and those over the size limit (refused by the backend anyway). */
export function splitBySize(files: File[]): {accepted: File[]; tooLarge: File[]} {
    return {
        accepted: files.filter(file => file.size <= MAX_UPLOAD_BYTES),
        tooLarge: files.filter(file => file.size > MAX_UPLOAD_BYTES),
    };
}

/** Folder and file name, as the chat names a document. */
export function documentPath(document: Pick<TenantDocument, "folder" | "fileName">): string {
    return document.folder ? `${document.folder}/${document.fileName}` : document.fileName;
}

/** Sorted like a file tree: by folder (the top level first), then file name - the browser's collation. */
export function sortByPath(documents: TenantDocument[]): TenantDocument[] {
    const collator = new Intl.Collator(undefined, {sensitivity: "base", numeric: true});
    return [...documents].sort(
        (a, b) => collator.compare(a.folder, b.folder) || collator.compare(a.fileName, b.fileName),
    );
}

/** How many files of an import had each outcome - the outcomes without a file left out. */
export function countOutcomes(files: ImportedFile[]): Partial<Record<ImportOutcome, number>> {
    const counts: Partial<Record<ImportOutcome, number>> = {};
    for (const file of files) {
        counts[file.outcome] = (counts[file.outcome] ?? 0) + 1;
    }
    return counts;
}

/** How many documents are in each state - for the line above the list. */
export interface DocumentProgress {
    total: number;
    ready: number;
    /** Waiting for their turn. */
    waiting: number;
    /** Being embedded now (and, for images and scans, read). */
    processing: number;
    /** Waiting or being processed. */
    open: number;
    failed: number;
}

export function documentProgress(documents: TenantDocument[]): DocumentProgress {
    const count = (...statuses: TenantDocument["status"][]) =>
        documents.filter(d => statuses.includes(d.status)).length;
    return {
        total: documents.length,
        ready: count("READY"),
        waiting: count("PENDING"),
        processing: count("PROCESSING"),
        open: count("PENDING", "PROCESSING"),
        failed: count("FAILED"),
    };
}

/** The list's filter: all, ready, still open (waiting or being processed), failed. */
export type DocumentFilter = "all" | "ready" | "open" | "failed";

const FILTER_STATUSES: Record<Exclude<DocumentFilter, "all">, DocumentStatus[]> = {
    ready: ["READY"],
    open: ["PENDING", "PROCESSING"],
    failed: ["FAILED"],
};

/** The documents of a filter whose path contains the search text (any case). */
export function filterDocuments(documents: TenantDocument[], query: string, filter: DocumentFilter): TenantDocument[] {
    const text = query.trim().toLocaleLowerCase();
    return documents.filter(
        document =>
            (filter === "all" || FILTER_STATUSES[filter].includes(document.status)) &&
            documentPath(document).toLocaleLowerCase().includes(text),
    );
}

/** A document's state as a chip: its label's translation key and tone (app-chip classes in styles.scss). */
export const DOCUMENT_STATUS_CHIPS: Record<DocumentStatus, {label: string; tone: string}> = {
    READY: {label: DOCUMENT_STATUS_LABELS.READY, tone: "ok"},
    PROCESSING: {label: DOCUMENT_STATUS_LABELS.PROCESSING, tone: "info running"},
    PENDING: {label: DOCUMENT_STATUS_LABELS.PENDING, tone: "warn"},
    FAILED: {label: DOCUMENT_STATUS_LABELS.FAILED, tone: "err"},
};

/**
 * The badge in front of a file's name: its extension, at most four letters ("PDF", "XLSX"); null without one (the
 * template shows the word for a file).
 */
export function fileTypeLabel(fileName: string): string | null {
    const dot = fileName.lastIndexOf(".");
    const extension = dot > 0 ? fileName.slice(dot + 1) : "";
    return extension && extension.length <= 4 ? extension.toUpperCase() : null;
}
