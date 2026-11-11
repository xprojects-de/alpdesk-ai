import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {Store} from "@ngrx/store";
import {interval, of} from "rxjs";
import {catchError, concatMap, filter, map, switchMap, takeUntil, withLatestFrom} from "rxjs/operators";
import {KnowledgeSourceService} from "../../services/knowledge-source.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as SourceActions from "./knowledge-source.actions";
import * as SourceSelectors from "./knowledge-source.selectors";
import {endedByLogout} from "../session.operator";

const POLL_INTERVAL_MS = 3000;

@Injectable()
export class KnowledgeSourceEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private store = inject(Store);
    private service = inject(KnowledgeSourceService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.loadSources, SourceActions.pollSources),
            switchMap(({scope}) =>
                this.service.list(scope).pipe(
                    map(sources => SourceActions.loadSourcesSuccess({scope, sources})),
                    catchError(error =>
                        of(
                            SourceActions.loadSourcesFailure({
                                error: this.errors.of(error, "errors.knowledgeSource.loadSources"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    /** While a source runs, its numbers are reloaded from the app - the store itself is never asked by this. */
    polling$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.startPolling),
            switchMap(({scope}) =>
                interval(POLL_INTERVAL_MS).pipe(
                    withLatestFrom(this.store.select(SourceSelectors.selectAnySourceRunning)),
                    filter(([, running]) => running),
                    map(() => SourceActions.pollSources({scope})),
                    takeUntil(this.actions$.pipe(ofType(SourceActions.stopPolling))),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    create$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.createSource),
            concatMap(({scope, request}) =>
                this.service.create(scope, request).pipe(
                    map(source => SourceActions.createSourceSuccess({scope, source})),
                    catchError(error =>
                        of(
                            SourceActions.createSourceFailure({
                                error: this.errors.of(error, "errors.knowledgeSource.createSource"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    update$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.updateSource),
            concatMap(({scope, sourceId, request}) =>
                this.service.update(scope, sourceId, request).pipe(
                    map(source => SourceActions.updateSourceSuccess({scope, source})),
                    catchError(error =>
                        of(
                            SourceActions.updateSourceFailure({
                                error: this.errors.of(error, "errors.knowledgeSource.updateSource"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.deleteSource),
            concatMap(({scope, sourceId, keepDocuments}) =>
                this.service.delete(scope, sourceId, keepDocuments).pipe(
                    map(() => SourceActions.deleteSourceSuccess({scope, sourceId})),
                    catchError(error =>
                        of(
                            SourceActions.deleteSourceFailure({
                                error: this.errors.of(error, "errors.knowledgeSource.deleteSource"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    run$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.runSource),
            concatMap(({scope, sourceId}) =>
                this.service.run(scope, sourceId).pipe(
                    map(source => SourceActions.runSourceSuccess({scope, source})),
                    catchError(error =>
                        of(
                            SourceActions.runSourceFailure({
                                error: this.errors.of(error, "errors.knowledgeSource.runSource"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    files$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.loadFiles),
            switchMap(({scope, sourceId}) =>
                this.service.files(scope, sourceId).pipe(
                    map(files => SourceActions.loadFilesSuccess({scope, sourceId, files})),
                    catchError(error =>
                        of(
                            SourceActions.loadFilesFailure({
                                error: this.errors.of(error, "errors.knowledgeSource.loadFiles"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    cancel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SourceActions.cancelSource),
            concatMap(({scope, sourceId}) =>
                this.service.cancel(scope, sourceId).pipe(
                    map(() => SourceActions.cancelSourceSuccess()),
                    catchError(error =>
                        of(
                            SourceActions.cancelSourceFailure({
                                error: this.errors.of(error, "errors.knowledgeSource.cancelSource"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
