import {createAction, props} from "@ngrx/store";
import {FileStore, FileStoreCheck, FileStoreFolders, FileStoreRequest} from "../../models/file-store.model";
import {TenantScope} from "../../models/tenant.model";

export const loadStores = createAction("[File Store] Load", props<{scope: TenantScope}>());
export const loadStoresSuccess = createAction(
    "[File Store] Load Success",
    props<{scope: TenantScope; stores: FileStore[]}>(),
);
export const loadStoresFailure = createAction("[File Store] Load Failure", props<{error: string}>());

export const createStore = createAction(
    "[File Store] Create",
    props<{scope: TenantScope; request: FileStoreRequest}>(),
);
export const createStoreSuccess = createAction(
    "[File Store] Create Success",
    props<{scope: TenantScope; store: FileStore}>(),
);
export const createStoreFailure = createAction("[File Store] Create Failure", props<{error: string}>());

export const updateStore = createAction(
    "[File Store] Update",
    props<{scope: TenantScope; storeId: number; request: FileStoreRequest}>(),
);
export const updateStoreSuccess = createAction(
    "[File Store] Update Success",
    props<{scope: TenantScope; store: FileStore}>(),
);
export const updateStoreFailure = createAction("[File Store] Update Failure", props<{error: string}>());

export const deleteStore = createAction("[File Store] Delete", props<{scope: TenantScope; storeId: number}>());
export const deleteStoreSuccess = createAction(
    "[File Store] Delete Success",
    props<{scope: TenantScope; storeId: number}>(),
);
export const deleteStoreFailure = createAction("[File Store] Delete Failure", props<{error: string}>());

/** Connects to the server: shows its fingerprint until one is confirmed, then logs in and reads the folder. */
export const checkStore = createAction("[File Store] Check", props<{scope: TenantScope; storeId: number}>());
/** error: why the check failed, translated when it came (status FAILED); null otherwise. */
export const checkStoreSuccess = createAction(
    "[File Store] Check Success",
    props<{scope: TenantScope; check: FileStoreCheck; error: string | null}>(),
);
export const checkStoreFailure = createAction("[File Store] Check Failure", props<{storeId: number; error: string}>());

export const confirmFingerprint = createAction(
    "[File Store] Confirm Fingerprint",
    props<{scope: TenantScope; storeId: number; fingerprint: string}>(),
);
export const confirmFingerprintSuccess = createAction(
    "[File Store] Confirm Fingerprint Success",
    props<{scope: TenantScope; store: FileStore}>(),
);
export const confirmFingerprintFailure = createAction(
    "[File Store] Confirm Fingerprint Failure",
    props<{error: string}>(),
);

/** Lists the subfolders of a folder of the store - while a folder is being chosen. */
export const browseFolders = createAction(
    "[File Store] Browse Folders",
    props<{scope: TenantScope; storeId: number; path: string}>(),
);
export const browseFoldersSuccess = createAction(
    "[File Store] Browse Folders Success",
    props<{scope: TenantScope; storeId: number; listing: FileStoreFolders}>(),
);
export const browseFoldersFailure = createAction(
    "[File Store] Browse Folders Failure",
    props<{storeId: number; path: string; error: string}>(),
);
