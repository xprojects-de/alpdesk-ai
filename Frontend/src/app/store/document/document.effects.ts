import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {Store} from "@ngrx/store";
import {from, interval, of} from "rxjs";
import {catchError, concatMap, filter, map, switchMap, takeUntil, withLatestFrom} from "rxjs/operators";
import {DocumentService} from "../../services/document.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as DocumentActions from "./document.actions";
import * as DocumentSelectors from "./document.selectors";
import {endedByLogout} from "../session.operator";

const POLL_INTERVAL_MS = 3000;

@Injectable()
export class DocumentEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private store = inject(Store);
    private documentService = inject(DocumentService);

    loadDocuments$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.loadDocuments, DocumentActions.pollDocuments),
            switchMap(({scope}) =>
                this.documentService.getAll(scope).pipe(
                    map(documents => DocumentActions.loadDocumentsSuccess({scope, documents})),
                    catchError(error =>
                        of(
                            DocumentActions.loadDocumentsFailure({
                                error: this.errors.of(error, "errors.document.loadDocuments"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    polling$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.startDocumentPolling),
            switchMap(({scope}) =>
                interval(POLL_INTERVAL_MS).pipe(
                    withLatestFrom(this.store.select(DocumentSelectors.selectHasPendingDocuments)),
                    filter(([, pending]) => pending),
                    map(() => DocumentActions.pollDocuments({scope})),
                    takeUntil(this.actions$.pipe(ofType(DocumentActions.stopDocumentPolling))),
                ),
            ),
        ),
    );

    importArchive$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.importArchive),
            concatMap(({scope, file, areaId}) =>
                this.documentService.importArchive(scope, file, areaId).pipe(
                    map(({files}) => DocumentActions.importArchiveSuccess({scope, fileName: file.name, files})),
                    catchError(error =>
                        of(
                            DocumentActions.importArchiveFailure({
                                scope,
                                fileName: file.name,
                                error: this.errors.of(error, "errors.document.importArchive"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    /**
     * After an import or a deletion of uploads the list is loaded anew (and the embedding followed by the polling) -
     * also after a failed import: a long one answers with a timeout while the server goes on storing the archive's
     * files, which the list then shows. Only while their tenant is shown: the admin may have opened another tenant's knowledge meanwhile, which the
     * reload would replace.
     */
    reloadAfterImport$ = createEffect(() =>
        this.actions$.pipe(
            ofType(
                DocumentActions.importArchiveSuccess,
                DocumentActions.importArchiveFailure,
                DocumentActions.deleteUploadsSuccess,
                DocumentActions.embedAllAgainSuccess,
            ),
            withLatestFrom(this.store.select(DocumentSelectors.selectDocumentScope)),
            filter(([{scope}, shown]) => scope === shown),
            map(([{scope}]) => DocumentActions.loadDocuments({scope})),
        ),
    );

    uploadDocuments$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.uploadDocuments),
            concatMap(({scope, files, areaId}) =>
                from(files).pipe(
                    concatMap(file =>
                        this.documentService.upload(scope, file, areaId).pipe(
                            map(document => DocumentActions.uploadDocumentSuccess({scope, document})),
                            catchError(error =>
                                of(
                                    DocumentActions.uploadDocumentFailure({
                                        fileName: file.name,
                                        error: this.errors.of(error, "errors.document.uploadDocument"),
                                    }),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    retryDocument$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.retryDocument),
            concatMap(({scope, id}) =>
                this.documentService.retry(scope, id).pipe(
                    map(document => DocumentActions.retryDocumentSuccess({scope, document})),
                    catchError(error =>
                        of(
                            DocumentActions.retryDocumentFailure({
                                error: this.errors.of(error, "errors.document.retryDocument"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    moveDocument$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.moveDocument),
            concatMap(({scope, id, areaId}) =>
                this.documentService.moveToArea(scope, id, areaId).pipe(
                    map(document => DocumentActions.moveDocumentSuccess({scope, document})),
                    catchError(error =>
                        of(
                            DocumentActions.moveDocumentFailure({
                                error: this.errors.of(error, "errors.document.moveDocument"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    embedAllAgain$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.embedAllAgain),
            concatMap(({scope}) =>
                this.documentService.embedAllAgain(scope).pipe(
                    map(({documents}) => DocumentActions.embedAllAgainSuccess({scope, documents})),
                    catchError(error =>
                        of(
                            DocumentActions.embedAllAgainFailure({
                                error: this.errors.of(error, "errors.document.embedAllAgain"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    deleteUploads$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.deleteUploads),
            concatMap(({scope, area, label}) =>
                this.documentService.deleteUploads(scope, area).pipe(
                    map(({deleted}) => DocumentActions.deleteUploadsSuccess({scope, label, deleted})),
                    catchError(error =>
                        of(
                            DocumentActions.deleteUploadsFailure({
                                error: this.errors.of(error, "errors.document.deleteUploads"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    deleteDocument$ = createEffect(() =>
        this.actions$.pipe(
            ofType(DocumentActions.deleteDocument),
            concatMap(({scope, id}) =>
                this.documentService.delete(scope, id).pipe(
                    map(() => DocumentActions.deleteDocumentSuccess({scope, id})),
                    catchError(error =>
                        of(
                            DocumentActions.deleteDocumentFailure({
                                error: this.errors.of(error, "errors.document.deleteDocument"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );
}
