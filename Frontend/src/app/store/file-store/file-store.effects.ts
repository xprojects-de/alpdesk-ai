import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {FileStoreService} from "../../services/file-store.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as FileStoreActions from "./file-store.actions";
import {endedByLogout} from "../session.operator";

@Injectable()
export class FileStoreEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private service = inject(FileStoreService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(FileStoreActions.loadStores),
            switchMap(({scope}) =>
                this.service.list(scope).pipe(
                    map(stores => FileStoreActions.loadStoresSuccess({scope, stores})),
                    catchError(error =>
                        of(
                            FileStoreActions.loadStoresFailure({
                                error: this.errors.of(error, "errors.fileStore.loadStores"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    create$ = createEffect(() =>
        this.actions$.pipe(
            ofType(FileStoreActions.createStore),
            concatMap(({scope, request}) =>
                this.service.create(scope, request).pipe(
                    map(store => FileStoreActions.createStoreSuccess({scope, store})),
                    catchError(error =>
                        of(
                            FileStoreActions.createStoreFailure({
                                error: this.errors.of(error, "errors.fileStore.createStore"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    update$ = createEffect(() =>
        this.actions$.pipe(
            ofType(FileStoreActions.updateStore),
            concatMap(({scope, storeId, request}) =>
                this.service.update(scope, storeId, request).pipe(
                    map(store => FileStoreActions.updateStoreSuccess({scope, store})),
                    catchError(error =>
                        of(
                            FileStoreActions.updateStoreFailure({
                                error: this.errors.of(error, "errors.fileStore.updateStore"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(FileStoreActions.deleteStore),
            concatMap(({scope, storeId}) =>
                this.service.delete(scope, storeId).pipe(
                    map(() => FileStoreActions.deleteStoreSuccess({scope, storeId})),
                    catchError(error =>
                        of(
                            FileStoreActions.deleteStoreFailure({
                                error: this.errors.of(error, "errors.fileStore.deleteStore"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    /** A failed check is an answer, not an HTTP error - its code is translated here, when it came. */
    check$ = createEffect(() =>
        this.actions$.pipe(
            ofType(FileStoreActions.checkStore),
            concatMap(({scope, storeId}) =>
                this.service.check(scope, storeId).pipe(
                    map(check =>
                        FileStoreActions.checkStoreSuccess({
                            scope,
                            check,
                            error:
                                check.status === "FAILED"
                                    ? this.errors.ofBody(
                                          {code: check.errorCode, params: check.errorParams, message: ""},
                                          "errors.fileStore.checkStore",
                                      )
                                    : null,
                        }),
                    ),
                    catchError(error =>
                        of(
                            FileStoreActions.checkStoreFailure({
                                storeId,
                                error: this.errors.of(error, "errors.fileStore.checkStore"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    browse$ = createEffect(() =>
        this.actions$.pipe(
            ofType(FileStoreActions.browseFolders),
            switchMap(({scope, storeId, path}) =>
                this.service.folders(scope, storeId, path).pipe(
                    map(listing => FileStoreActions.browseFoldersSuccess({scope, storeId, listing})),
                    catchError(error =>
                        of(
                            FileStoreActions.browseFoldersFailure({
                                storeId,
                                path,
                                error: this.errors.of(error, "errors.fileStore.browseFolders"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    confirm$ = createEffect(() =>
        this.actions$.pipe(
            ofType(FileStoreActions.confirmFingerprint),
            concatMap(({scope, storeId, fingerprint}) =>
                this.service.confirmFingerprint(scope, storeId, fingerprint).pipe(
                    map(store => FileStoreActions.confirmFingerprintSuccess({scope, store})),
                    catchError(error =>
                        of(
                            FileStoreActions.confirmFingerprintFailure({
                                error: this.errors.of(error, "errors.fileStore.confirmFingerprint"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );
}
