import {TenantDocument} from "../../models/document.model";
import * as DocumentActions from "./document.actions";
import {documentReducer, initialState} from "./document.reducer";

describe("documentReducer", () => {
    const document = (id: number, status: TenantDocument["status"] = "PENDING") =>
        ({id, fileName: `${id}.pdf`, status}) as TenantDocument;
    const file = (name: string) => ({name, size: 1}) as File;
    const ownList = documentReducer(initialState, DocumentActions.loadDocuments({scope: "self"}));

    it("counts the files still to upload down per answer, success or failure", () => {
        let state = documentReducer(
            ownList,
            DocumentActions.uploadDocuments({scope: "self", files: [file("a"), file("b")], areaId: null}),
        );
        expect(state.uploading).toBe(2);

        state = documentReducer(state, DocumentActions.uploadDocumentSuccess({scope: "self", document: document(1)}));
        state = documentReducer(state, DocumentActions.uploadDocumentFailure({fileName: "b", error: "too large"}));

        expect(state.uploading).toBe(0);
        expect(state.documents.map(d => d.id)).toEqual([1]);
    });

    it("marks an archive import running until it answers, success or failure", () => {
        const started = documentReducer(
            initialState,
            DocumentActions.importArchive({scope: "self", file: file("a.zip"), areaId: null}),
        );
        expect(started.importing).toBe(true);
        expect(
            documentReducer(
                started,
                DocumentActions.importArchiveSuccess({scope: "self", fileName: "a.zip", files: []}),
            ).importing,
        ).toBe(false);
        expect(
            documentReducer(
                started,
                DocumentActions.importArchiveFailure({scope: "self", fileName: "a.zip", error: "kaputt"}),
            ).importing,
        ).toBe(false);
    });

    it("puts an uploaded document first and replaces a retried one in place", () => {
        let state = documentReducer(
            ownList,
            DocumentActions.loadDocumentsSuccess({
                scope: "self",
                documents: [document(1, "READY"), document(2, "FAILED")],
            }),
        );
        state = documentReducer(state, DocumentActions.uploadDocumentSuccess({scope: "self", document: document(3)}));
        state = documentReducer(
            state,
            DocumentActions.retryDocumentSuccess({scope: "self", document: document(2, "PENDING")}),
        );

        expect(state.documents.map(d => [d.id, d.status])).toEqual([
            [3, "PENDING"],
            [1, "READY"],
            [2, "PENDING"],
        ]);
    });

    it("drops the documents of another list when a list is opened", () => {
        const loaded = documentReducer(
            documentReducer(initialState, DocumentActions.loadDocuments({scope: 1})),
            DocumentActions.loadDocumentsSuccess({scope: 1, documents: [document(1)]}),
        );

        const state = documentReducer(loaded, DocumentActions.loadDocuments({scope: 2}));

        expect(state.scope).toBe(2);
        expect(state.documents).toEqual([]);
    });

    it("ignores results for a list no longer open", () => {
        const state = documentReducer(initialState, DocumentActions.loadDocuments({scope: 2}));

        expect(documentReducer(state, DocumentActions.loadDocumentsSuccess({scope: 1, documents: [document(1)]}))).toBe(
            state,
        );
        expect(documentReducer(state, DocumentActions.deleteDocumentSuccess({scope: 1, id: 1}))).toBe(state);
        expect(
            documentReducer(state, DocumentActions.uploadDocumentSuccess({scope: 1, document: document(5)})).documents,
        ).toEqual([]);
    });

    it("ignores a list answered after the logout, before the next login opened one", () => {
        expect(
            documentReducer(
                initialState,
                DocumentActions.loadDocumentsSuccess({scope: "self", documents: [document(1)]}),
            ),
        ).toBe(initialState);
    });
});
