import {createReducer, on} from "@ngrx/store";
import {TenantDocument} from "../../models/document.model";
import {TenantScope} from "../../models/tenant.model";
import * as DocumentActions from "./document.actions";

/** The company knowledge on screen - one tenant's at a time: the tenant admin's own, or the one the admin opened. */
export interface DocumentState {
    scope: TenantScope | null;
    documents: TenantDocument[];
    loading: boolean;
    /** Files still to upload (the running one included). */
    uploading: number;
    /** A ZIP archive is being imported. */
    importing: boolean;
}

export const initialState: DocumentState = {
    scope: null,
    documents: [],
    loading: false,
    uploading: 0,
    importing: false,
};

/** Replaces the document if it is listed, else puts it first (the list is newest first). */
function upsert(documents: TenantDocument[], document: TenantDocument): TenantDocument[] {
    return documents.some(d => d.id === document.id)
        ? documents.map(d => (d.id === document.id ? document : d))
        : [document, ...documents];
}

export const documentReducer = createReducer(
    initialState,

    on(DocumentActions.loadDocuments, (state, {scope}) => ({
        ...state,
        scope,
        documents: state.scope === scope ? state.documents : [],
        loading: true,
    })),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(DocumentActions.loadDocumentsSuccess, (state, {scope, documents}) =>
        state.scope === scope ? {...state, documents, loading: false} : state,
    ),
    on(DocumentActions.loadDocumentsFailure, state => ({...state, loading: false})),

    on(DocumentActions.uploadDocuments, (state, {files}) => ({...state, uploading: state.uploading + files.length})),
    on(DocumentActions.uploadDocumentSuccess, (state, {scope, document}) => ({
        ...state,
        documents: state.scope === scope ? upsert(state.documents, document) : state.documents,
        uploading: Math.max(0, state.uploading - 1),
    })),
    on(DocumentActions.uploadDocumentFailure, state => ({...state, uploading: Math.max(0, state.uploading - 1)})),

    on(DocumentActions.importArchive, state => ({...state, importing: true})),
    on(DocumentActions.importArchiveSuccess, DocumentActions.importArchiveFailure, state => ({
        ...state,
        importing: false,
    })),

    on(DocumentActions.retryDocumentSuccess, DocumentActions.moveDocumentSuccess, (state, {scope, document}) =>
        state.scope === scope ? {...state, documents: upsert(state.documents, document)} : state,
    ),
    on(DocumentActions.deleteDocumentSuccess, (state, {scope, id}) =>
        state.scope === scope ? {...state, documents: state.documents.filter(d => d.id !== id)} : state,
    ),
);
