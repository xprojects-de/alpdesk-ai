/**
 * How a file store is reached: a server (SFTP, FTPS), a bucket of S3 or a compatible store, or a document library of a
 * SharePoint site or a OneDrive (SHAREPOINT).
 */
export type FileStoreType = "SFTP" | "FTPS" | "S3" | "SHAREPOINT" | "IMAP";

/** The types reached as a server with host, port, user and the fingerprint of its key or certificate. */
export type ServerStoreType = Exclude<FileStoreType, "S3" | "SHAREPOINT" | "IMAP">;

export const FILE_STORE_TYPES: FileStoreType[] = ["SFTP", "FTPS", "S3", "SHAREPOINT", "IMAP"];

/** How a mailbox is reached: IMAPS, STARTTLS - or unencrypted, only to a host the installation names internal. */
export type MailboxSecurity = "SSL" | "STARTTLS" | "NONE";

export const MAILBOX_SECURITIES: MailboxSecurity[] = ["SSL", "STARTTLS", "NONE"];

/** A mailbox's port when none is entered. */
export const MAILBOX_PORTS: Record<MailboxSecurity, number> = {SSL: 993, STARTTLS: 143, NONE: 143};

/** Each server type's port when none is entered. */
export const DEFAULT_PORTS: Record<ServerStoreType, number> = {SFTP: 22, FTPS: 21};

/** The region a compatible S3 store gets when none is entered - Amazon S3 needs its own. */
export const DEFAULT_S3_REGION = "us-east-1";

/**
 * A server or bucket the app reads files from and writes files to for the tenant - its password or secret never comes
 * back. host, port, username and fingerprint (the server's key or certificate a tenant admin confirmed) belong to SFTP
 * and FTPS; endpoint (null: Amazon S3), region, bucket and accessKeyId to S3; directoryId (the customer's Entra ID),
 * clientId (the app registration), siteUrl and library (null: the site's default one) to SharePoint. checkedAt /
 * checkError: the last check (checkError null after a check: it worked).
 */
export interface FileStore {
    id: number;
    name: string;
    type: FileStoreType;
    host: string | null;
    port: number | null;
    username: string | null;
    basePath: string;
    fingerprint: string | null;
    endpoint: string | null;
    region: string | null;
    bucket: string | null;
    accessKeyId: string | null;
    directoryId: string | null;
    clientId: string | null;
    siteUrl: string | null;
    library: string | null;
    checkedAt: string | null;
    checkError: string | null;
    /** Scheduled tasks may write into it (a CSV of rows) - off unless a tenant admin turns it on; never a mailbox. */
    taskWrite: boolean;
    /** IMAP: how it is reached, its mailbox folder and the days of messages listed. */
    security: MailboxSecurity | null;
    mailbox: string | null;
    days: number | null;
}

/**
 * The subfolders of one folder of a store, to choose one: path relative to the store's folder ("" its folder itself),
 * truncated - more folders than the list holds.
 */
export interface FileStoreFolders {
    path: string;
    folders: string[];
    truncated: boolean;
}

/**
 * The password (S3: the secret access key, SharePoint: the client secret) left out: unchanged - another host or port
 * (S3: endpoint, region, bucket or access key; SharePoint: directory or app) needs it again.
 */
export interface FileStoreRequest {
    name: string;
    type: FileStoreType;
    host?: string;
    port?: number;
    username?: string;
    password?: string;
    basePath?: string;
    endpoint?: string;
    region?: string;
    bucket?: string;
    accessKeyId?: string;
    directoryId?: string;
    clientId?: string;
    siteUrl?: string;
    library?: string;
    taskWrite: boolean;
    security?: MailboxSecurity;
    mailbox?: string;
    days?: number;
}

/**
 * What a check found: OK (logged in, entries are the folder's first names), UNCONFIRMED (the server's fingerprint is
 * to be confirmed - nothing was logged in to), CHANGED (another key or certificate than the confirmed one - nothing was
 * logged in to), FAILED (errorCode says why).
 */
export interface FileStoreCheck {
    status: "OK" | "UNCONFIRMED" | "CHANGED" | "FAILED";
    fingerprint: string | null;
    entries: string[];
    errorCode: string | null;
    errorParams: Record<string, string>;
    store: FileStore;
}
