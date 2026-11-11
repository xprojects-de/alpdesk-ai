import {
    DEFAULT_PORTS,
    DEFAULT_S3_REGION,
    FileStore,
    FileStoreRequest,
    FileStoreType,
    MAILBOX_PORTS,
    MailboxSecurity,
} from "../models/file-store.model";

export interface FileStoreFormValue {
    name: string;
    type: FileStoreType;
    host: string;
    port: number | null;
    username: string;
    /** SFTP, FTPS: the password; S3: the secret access key; SharePoint: the app's client secret. */
    password: string;
    basePath: string;
    endpoint: string;
    region: string;
    bucket: string;
    accessKeyId: string;
    directoryId: string;
    clientId: string;
    siteUrl: string;
    library: string;
    taskWrite: boolean;
    security: MailboxSecurity;
    mailbox: string;
    days: number | null;
}

/** The fields of the dialog a type requires - S3 needs a region only for Amazon S3 (no endpoint). */
export function requiredFields(type: FileStoreType, endpoint: string): (keyof FileStoreFormValue)[] {
    switch (type) {
        case "S3":
            return endpoint.trim() ? ["bucket", "accessKeyId"] : ["bucket", "accessKeyId", "region"];
        case "SHAREPOINT":
            return ["directoryId", "clientId", "siteUrl"];
        default:
            return ["host", "username"];
    }
}

export interface FieldTexts {
    secret: string;
    secretHint: string;
    /** Shown when another server, bucket or app needs the secret again. */
    secretAgain: string;
    folderHint: string;
}

/** The translation keys of the secret's label and hints and of the folder's hint - each type names them its way. */
export function fieldTexts(type: FileStoreType): FieldTexts {
    switch (type) {
        case "S3":
            return {
                secret: "fileStores.dialog.secretKey",
                secretHint: "fileStores.dialog.secretKeyHint",
                secretAgain: "fileStores.dialog.secretKeyAgain",
                folderHint: "fileStores.dialog.basePathHintS3",
            };
        case "SHAREPOINT":
            return {
                secret: "fileStores.dialog.clientSecret",
                secretHint: "fileStores.dialog.clientSecretHint",
                secretAgain: "fileStores.dialog.clientSecretAgain",
                folderHint: "fileStores.dialog.basePathHintSharePoint",
            };
        default:
            return {
                secret: "fileStores.dialog.password",
                secretHint: "fileStores.dialog.passwordHint",
                secretAgain: "fileStores.dialog.passwordAgain",
                folderHint: "fileStores.dialog.basePathHint",
            };
    }
}

/**
 * The request of the store dialog, with the fields of its type only: an empty port, folder, endpoint or region is
 * left out (the backend takes the type's port, "/", Amazon S3 and the default region), an empty password too - the
 * stored one stays. The password is sent as typed: it may begin or end with a space.
 */
export function buildFileStoreRequest(value: FileStoreFormValue): FileStoreRequest {
    const request: FileStoreRequest = {name: value.name.trim(), type: value.type, taskWrite: value.taskWrite};
    if (value.type === "IMAP") {
        request.host = value.host.trim();
        request.username = value.username.trim();
        request.security = value.security;
        request.taskWrite = false;
        if (value.port !== null) {
            request.port = value.port;
        }
        if (value.mailbox.trim()) {
            request.mailbox = value.mailbox.trim();
        }
        if (value.days !== null) {
            request.days = value.days;
        }
    } else if (value.type === "SHAREPOINT") {
        request.directoryId = value.directoryId.trim();
        request.clientId = value.clientId.trim();
        request.siteUrl = value.siteUrl.trim();
        if (value.library.trim()) {
            request.library = value.library.trim();
        }
    } else if (value.type === "S3") {
        request.bucket = value.bucket.trim();
        request.accessKeyId = value.accessKeyId.trim();
        if (value.endpoint.trim()) {
            request.endpoint = value.endpoint.trim();
        }
        if (value.region.trim()) {
            request.region = value.region.trim();
        }
    } else {
        request.host = value.host.trim();
        request.username = value.username.trim();
        if (value.port !== null) {
            request.port = value.port;
        }
    }
    if (value.password) {
        request.password = value.password;
    }
    if (value.basePath.trim() && value.type !== "IMAP") {
        request.basePath = value.basePath.trim();
    }
    return request;
}

/**
 * Whether the dialog must ask for the password: always for a new store, and for another server (type, host or port;
 * S3: endpoint, region, bucket or access key; SharePoint: directory or app - another site keeps it, the secret goes to
 * Microsoft's login only) - the backend does not send the stored password to a server it was not entered for.
 */
export function passwordNeeded(
    store: FileStore | null,
    value: Pick<
        FileStoreFormValue,
        "type" | "host" | "port" | "endpoint" | "region" | "bucket" | "accessKeyId" | "directoryId" | "clientId"
    > &
        Partial<Pick<FileStoreFormValue, "username" | "security">>,
): boolean {
    if (store === null || value.type !== store.type) {
        return true;
    }
    if (value.type === "IMAP") {
        // another server, account or way of reaching it - the backend does not send the password there unasked
        const security = value.security ?? "SSL";
        const port = value.port ?? MAILBOX_PORTS[security];
        return (
            value.host.trim().toLowerCase() !== store.host ||
            port !== store.port ||
            (value.username ?? "").trim() !== store.username ||
            security !== store.security
        );
    }
    if (value.type === "SHAREPOINT") {
        return (
            value.directoryId.trim().toLowerCase() !== store.directoryId ||
            value.clientId.trim().toLowerCase() !== store.clientId
        );
    }
    if (value.type === "S3") {
        return (
            endpointOf(value.endpoint) !== store.endpoint ||
            regionOf(value.endpoint, value.region) !== store.region ||
            value.bucket.trim() !== store.bucket ||
            value.accessKeyId.trim() !== store.accessKeyId
        );
    }
    // An empty port is the type's own - the backend takes that, so it may be another server than before.
    const port = value.port ?? DEFAULT_PORTS[value.type as keyof typeof DEFAULT_PORTS];
    return value.host.trim().toLowerCase() !== store.host || port !== store.port;
}

/** The endpoint as the backend stores it: scheme and host in lower case, no trailing slash; null for none. */
function endpointOf(endpoint: string): string | null {
    const trimmed = endpoint.trim().replace(/\/+$/, "");
    return trimmed ? trimmed.toLowerCase() : null;
}

/** The region as the backend stores it: lower case, the default one for a compatible store when none is entered. */
function regionOf(endpoint: string, region: string): string | null {
    const trimmed = region.trim().toLowerCase();
    if (trimmed) {
        return trimmed;
    }
    return endpoint.trim() ? DEFAULT_S3_REGION : null;
}

/**
 * Where the store is, as the list shows it: "host:port", the bucket at its endpoint (Amazon S3 and its region), or the
 * site's address with its library.
 */
export function storeAddress(store: FileStore): string {
    if (store.type === "IMAP") {
        return `${store.username}@${store.host} · ${store.mailbox}`;
    }
    if (store.type === "SHAREPOINT") {
        return store.library ? `${store.siteUrl} · ${store.library}` : `${store.siteUrl}`;
    }
    if (store.type === "S3") {
        return `${store.bucket} · ${store.endpoint ?? `Amazon S3 ${store.region}`}`;
    }
    return `${store.host}:${store.port}`;
}

export type FileStoreChip = "ok" | "warn" | "err" | null;

/** The store's state after its last check: the chip's class and a translation key. */
export function storeStatus(store: FileStore): {chip: FileStoreChip; label: string} {
    if (store.checkedAt === null) {
        return {chip: null, label: "fileStores.status.unchecked"};
    }
    switch (store.checkError) {
        case null:
            return {chip: "ok", label: "fileStores.status.ok"};
        case "fileStore.fingerprintUnconfirmed":
            return {chip: "warn", label: "fileStores.status.unconfirmed"};
        case "fileStore.fingerprintChanged":
            return {chip: "err", label: "fileStores.status.changed"};
        default:
            return {chip: "err", label: "fileStores.status.failed"};
    }
}

/** One step of a folder's path - its name and the path up to it ("" for the store's own folder). */
export interface FolderStep {
    name: string;
    path: string;
}

/** The steps of a path below the store's folder, to jump back: "Projekte/2025" - Projekte, Projekte/2025. */
export function folderSteps(path: string): FolderStep[] {
    const steps: FolderStep[] = [];
    let current = "";
    for (const name of path.split("/").filter(part => part !== "")) {
        current = current === "" ? name : `${current}/${name}`;
        steps.push({name, path: current});
    }
    return steps;
}

/** The path of a subfolder of this folder. */
export function childFolder(path: string, name: string): string {
    return path === "" ? name : `${path}/${name}`;
}
