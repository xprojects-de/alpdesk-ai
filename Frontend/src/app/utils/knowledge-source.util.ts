import {FileStore} from "../models/file-store.model";
import {KnowledgeSource, KnowledgeSourceRequest, SourceSchedule} from "../models/knowledge-source.model";

export interface KnowledgeSourceFormValue {
    name: string;
    fileStoreId: number | null;
    folder: string;
    areaId: number | null;
    pattern: string;
    schedule: SourceSchedule;
    active: boolean;
}

/** The request of the source dialog: trimmed, an empty pattern left out (every file). */
export function buildSourceRequest(value: KnowledgeSourceFormValue): KnowledgeSourceRequest {
    const request: KnowledgeSourceRequest = {
        name: value.name.trim(),
        fileStoreId: value.fileStoreId ?? 0,
        folder: value.folder.trim(),
        areaId: value.areaId,
        schedule: value.schedule,
        active: value.active,
    };
    if (value.pattern.trim()) {
        request.pattern = value.pattern.trim();
    }
    return request;
}

/** "Projekte//2025/" and "/Projekte/2025" are the same folder - as the backend keeps it. */
export function normalizedFolder(folder: string): string {
    return folder
        .trim()
        .replace(/\/+/g, "/")
        .replace(/^\/+|\/+$/g, "");
}

/**
 * Whether saving makes the source read other files - another store or folder: its documents are deleted and it is
 * read anew. The dialog says so before it is saved.
 */
export function readsOtherFiles(
    source: KnowledgeSource | null,
    value: Pick<KnowledgeSourceFormValue, "fileStoreId" | "folder">,
): boolean {
    return (
        source !== null &&
        (value.fileStoreId !== source.fileStoreId || normalizedFolder(value.folder) !== source.folder)
    );
}

export type SourceChip = "ok" | "warn" | "err" | "info" | null;

/**
 * The source's state: the chip's class and a translation key. since: when it last worked, for a failure after an
 * earlier success ("nicht erreichbar seit ...").
 */
export function sourceStatus(source: KnowledgeSource): {chip: SourceChip; label: string; since: string | null} {
    if (source.running) {
        return {chip: "info", label: "knowledgeSources.status.running", since: null};
    }
    if (source.lastFinishedAt === null) {
        return {chip: null, label: "knowledgeSources.status.new", since: null};
    }
    switch (source.lastError) {
        case null:
            return {chip: "ok", label: "knowledgeSources.status.ok", since: null};
        case "source.cancelled":
            return {chip: "warn", label: "knowledgeSources.status.cancelled", since: null};
        case "source.tooManyFiles":
            return {chip: "warn", label: "knowledgeSources.status.incomplete", since: null};
        default:
            return source.lastSucceededAt === null
                ? {chip: "err", label: "knowledgeSources.status.failed", since: null}
                : {chip: "err", label: "knowledgeSources.status.failedSince", since: source.lastSucceededAt};
    }
}

/**
 * What the backend's codes of a source name in their texts - the store (its server, bucket, site or library), the
 * folder, the limits of a document.
 */
export function sourceErrorParams(source: KnowledgeSource, store?: FileStore): Record<string, string> {
    return {
        host: store?.host ?? source.fileStore ?? "",
        store: source.fileStore ?? "",
        bucket: store?.bucket ?? "",
        site: store?.siteUrl ?? "",
        library: store?.library ?? "",
        path: source.folder || "/",
        maxMb: "50",
        max: "20000000",
    };
}

/**
 * Another source of the same store whose folder is this one, lies above or below it - both read the same files, and
 * the documents would stand twice in the knowledge. Saving is allowed (two patterns may keep them apart); the dialog
 * names it. Null when there is none.
 */
export function overlappingSource(
    sources: KnowledgeSource[],
    sourceId: number | null,
    storeId: number | null,
    folder: string,
): KnowledgeSource | null {
    const mine = normalizedFolder(folder);
    const inside = (outer: string, inner: string) => outer === "" || inner === outer || inner.startsWith(outer + "/");
    return (
        sources.find(
            other =>
                other.id !== sourceId &&
                other.fileStoreId === storeId &&
                (inside(other.folder, mine) || inside(mine, other.folder)),
        ) ?? null
    );
}

/**
 * The translation key of why a source's file has no document: the refusal's code, or null - its document was deleted by
 * hand. A type not read gets a text of its own: the upload's names the detected type, which the source does not keep. So
 * does a file the store could not read: the code's own text needs the path as a parameter, the list has none.
 */
export function sourceFileReason(reason: string | null): string {
    if (reason === null) {
        return "knowledgeSources.files.deletedByHand";
    }
    if (reason.startsWith("fileStore.")) {
        return "knowledgeSources.files.unreadable";
    }
    if (reason === "document.typeNotSupported") {
        return "knowledgeSources.files.typeNotSupported";
    }
    return "errors.backend." + reason;
}
