import {createAction, props} from "@ngrx/store";
import {ImportedFile, TenantDocument} from "../../models/document.model";
import {TenantScope} from "../../models/tenant.model";

export const loadDocuments = createAction("[Document] Load Documents", props<{scope: TenantScope}>());
/** A reload by the polling, without the loading indicator. */
export const pollDocuments = createAction("[Document] Poll Documents", props<{scope: TenantScope}>());
export const loadDocumentsSuccess = createAction(
    "[Document] Load Documents Success",
    props<{scope: TenantScope; documents: TenantDocument[]}>(),
);
export const loadDocumentsFailure = createAction("[Document] Load Documents Failure", props<{error: string}>());

/** While the list is shown: reloads it every few seconds as long as a document is being embedded. */
export const startDocumentPolling = createAction("[Document] Start Polling", props<{scope: TenantScope}>());
export const stopDocumentPolling = createAction("[Document] Stop Polling");

/**
 * Uploads the files one after the other into the area (null: the company knowledge); each one answers with its own
 * success or failure.
 */
export const uploadDocuments = createAction(
    "[Document] Upload Documents",
    props<{scope: TenantScope; files: File[]; areaId: number | null}>(),
);
export const uploadDocumentSuccess = createAction(
    "[Document] Upload Document Success",
    props<{scope: TenantScope; document: TenantDocument}>(),
);
export const uploadDocumentFailure = createAction(
    "[Document] Upload Document Failure",
    props<{fileName: string; error: string}>(),
);

export const importArchive = createAction(
    "[Document] Import Archive",
    props<{scope: TenantScope; file: File; areaId: number | null}>(),
);
export const importArchiveSuccess = createAction(
    "[Document] Import Archive Success",
    props<{scope: TenantScope; fileName: string; files: ImportedFile[]}>(),
);
export const importArchiveFailure = createAction(
    "[Document] Import Archive Failure",
    props<{scope: TenantScope; fileName: string; error: string}>(),
);

export const retryDocument = createAction("[Document] Retry Document", props<{scope: TenantScope; id: number}>());
export const retryDocumentSuccess = createAction(
    "[Document] Retry Document Success",
    props<{scope: TenantScope; document: TenantDocument}>(),
);
export const retryDocumentFailure = createAction("[Document] Retry Document Failure", props<{error: string}>());

/** Moves a document to an area, or to the company knowledge (areaId null). */
export const moveDocument = createAction(
    "[Document] Move Document",
    props<{scope: TenantScope; id: number; areaId: number | null}>(),
);
export const moveDocumentSuccess = createAction(
    "[Document] Move Document Success",
    props<{scope: TenantScope; document: TenantDocument}>(),
);
export const moveDocumentFailure = createAction("[Document] Move Document Failure", props<{error: string}>());

/** Embeds every document again with the tenant's model - the chunks get what changed since (their header). */
export const embedAllAgain = createAction("[Document] Embed All Again", props<{scope: TenantScope}>());
export const embedAllAgainSuccess = createAction(
    "[Document] Embed All Again Success",
    props<{scope: TenantScope; documents: number}>(),
);
export const embedAllAgainFailure = createAction("[Document] Embed All Again Failure", props<{error: string}>());

/** Deletes every document uploaded into the company knowledge (area "company") or an area (its id). */
export const deleteUploads = createAction(
    "[Document] Delete Uploads",
    props<{scope: TenantScope; area: string; label: string}>(),
);
export const deleteUploadsSuccess = createAction(
    "[Document] Delete Uploads Success",
    props<{scope: TenantScope; label: string; deleted: number}>(),
);
export const deleteUploadsFailure = createAction("[Document] Delete Uploads Failure", props<{error: string}>());

export const deleteDocument = createAction("[Document] Delete Document", props<{scope: TenantScope; id: number}>());
export const deleteDocumentSuccess = createAction(
    "[Document] Delete Document Success",
    props<{scope: TenantScope; id: number}>(),
);
export const deleteDocumentFailure = createAction("[Document] Delete Document Failure", props<{error: string}>());
