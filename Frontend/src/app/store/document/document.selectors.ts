import {createFeatureSelector, createSelector} from "@ngrx/store";
import {hasPendingDocuments} from "../../utils/document.util";
import {DocumentState} from "./document.reducer";

export const selectDocumentState = createFeatureSelector<DocumentState>("document");

export const selectDocuments = createSelector(selectDocumentState, state => state.documents);

/** The tenant whose documents the slice holds. */
export const selectDocumentScope = createSelector(selectDocumentState, state => state.scope);

export const selectDocumentsLoading = createSelector(selectDocumentState, state => state.loading);

export const selectUploading = createSelector(selectDocumentState, state => state.uploading);

export const selectImporting = createSelector(selectDocumentState, state => state.importing);

export const selectHasPendingDocuments = createSelector(selectDocuments, hasPendingDocuments);
