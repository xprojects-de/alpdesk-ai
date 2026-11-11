import {createFeatureSelector, createSelector} from "@ngrx/store";
import {FileStoreState} from "./file-store.reducer";

export const selectFileStoreState = createFeatureSelector<FileStoreState>("fileStore");

export const selectStores = createSelector(selectFileStoreState, state => state.stores);

export const selectStoresLoading = createSelector(selectFileStoreState, state => state.loading);

export const selectCheckingId = createSelector(selectFileStoreState, state => state.checkingId);

/** The folder's first names per store id - a store not checked in this session has none. */
export const selectEntries = createSelector(selectFileStoreState, state => state.entries);

/** The folder being looked into while one is chosen. */
export const selectBrowsing = createSelector(selectFileStoreState, state => state.browsing);
