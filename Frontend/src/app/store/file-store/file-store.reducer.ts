import {createReducer, on} from "@ngrx/store";
import {FileStore, FileStoreFolders} from "../../models/file-store.model";
import {TenantScope} from "../../models/tenant.model";
import * as FileStoreActions from "./file-store.actions";

/** One tenant's file stores at a time: the tenant admin's own, or the tenant the admin opened. */
export interface FileStoreState {
    scope: TenantScope | null;
    stores: FileStore[];
    loading: boolean;
    /** The store being checked. */
    checkingId: number | null;
    /** The folder's first names per store, as its last check in this session read them. */
    entries: Record<number, string[]>;
    /** The folder being looked into while one is chosen; null while none is. */
    browsing: FolderBrowsing | null;
}

/** A folder of a store being looked into: its subfolders once listed, or why they could not be. */
export interface FolderBrowsing {
    storeId: number;
    path: string;
    listing: FileStoreFolders | null;
    error: string | null;
}

export const initialState: FileStoreState = {
    scope: null,
    stores: [],
    loading: false,
    checkingId: null,
    entries: {},
    browsing: null,
};

function replace(stores: FileStore[], store: FileStore): FileStore[] {
    return stores.map(existing => (existing.id === store.id ? store : existing));
}

function byName(stores: FileStore[]): FileStore[] {
    return [...stores].sort((a, b) => a.name.localeCompare(b.name));
}

function withoutEntries(entries: Record<number, string[]>, storeId: number): Record<number, string[]> {
    const rest = {...entries};
    delete rest[storeId];
    return rest;
}

export const fileStoreReducer = createReducer(
    initialState,

    on(FileStoreActions.loadStores, (state, {scope}) =>
        state.scope === scope ? {...state, loading: true} : {...initialState, scope, loading: true},
    ),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(FileStoreActions.loadStoresSuccess, (state, {scope, stores}) =>
        state.scope === scope ? {...state, stores, loading: false} : state,
    ),
    on(FileStoreActions.loadStoresFailure, state => ({...state, loading: false})),

    on(FileStoreActions.createStoreSuccess, (state, {scope, store}) =>
        state.scope === scope ? {...state, stores: byName([...state.stores, store])} : state,
    ),
    // A changed store or a newly confirmed fingerprint makes the last listing stale.
    on(FileStoreActions.updateStoreSuccess, FileStoreActions.confirmFingerprintSuccess, (state, {scope, store}) =>
        state.scope === scope
            ? {...state, stores: byName(replace(state.stores, store)), entries: withoutEntries(state.entries, store.id)}
            : state,
    ),
    on(FileStoreActions.deleteStoreSuccess, (state, {scope, storeId}) =>
        state.scope === scope
            ? {
                  ...state,
                  stores: state.stores.filter(s => s.id !== storeId),
                  entries: withoutEntries(state.entries, storeId),
              }
            : state,
    ),

    on(FileStoreActions.checkStore, (state, {storeId}) => ({...state, checkingId: storeId})),
    on(FileStoreActions.checkStoreSuccess, (state, {scope, check}) => {
        if (state.scope !== scope) {
            return {...state, checkingId: null};
        }
        const entries =
            check.status === "OK"
                ? {...state.entries, [check.store.id]: check.entries}
                : withoutEntries(state.entries, check.store.id);
        return {...state, stores: replace(state.stores, check.store), entries, checkingId: null};
    }),
    on(FileStoreActions.checkStoreFailure, state => ({...state, checkingId: null})),

    on(FileStoreActions.browseFolders, (state, {storeId, path}) => ({
        ...state,
        browsing: {storeId, path, listing: null, error: null},
    })),
    // Only the folder asked for last: a slow answer for one clicked before is dropped.
    on(FileStoreActions.browseFoldersSuccess, (state, {scope, storeId, listing}) =>
        state.scope === scope && state.browsing?.storeId === storeId
            ? {...state, browsing: {storeId, path: listing.path, listing, error: null}}
            : state,
    ),
    on(FileStoreActions.browseFoldersFailure, (state, {storeId, path, error}) =>
        state.browsing?.storeId === storeId && state.browsing.path === path
            ? {...state, browsing: {...state.browsing, error}}
            : state,
    ),
);
