/**
 * A handbook's website: the static website uploaded for the whole tenant (areaId null) or one knowledge area, read by
 * people beside the chat - the assistant does not know it.
 */
export interface DocSite {
    id: number;
    areaId: number | null;
    areaName: string | null;
    fileCount: number;
    sizeBytes: number;
    uploadedAt: string;
}

/** A file of the ZIP that is not part of the website, and why (English, from the backend). */
export interface SkippedFile {
    path: string;
    reason: string;
}

/** The websites a tenant's admins keep, and the largest ZIP the backend takes (app.doc-site.max-zip-size). */
export interface DocSiteList {
    sites: DocSite[];
    maxZipBytes: number;
}

export interface DocSiteUpload {
    site: DocSite;
    skipped: SkippedFile[];
}

/** Where to read a website for the next hour; scope null: as a tenant user, through its handbook. */
export interface OpenedDocSite {
    siteId: number;
    url: string;
}
