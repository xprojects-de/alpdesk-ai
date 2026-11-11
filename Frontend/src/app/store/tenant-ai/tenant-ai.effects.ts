import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {TenantAiService} from "../../services/tenant-ai.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {endedByLogout} from "../session.operator";
import * as TenantAiActions from "./tenant-ai.actions";

@Injectable()
export class TenantAiEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private tenantAiService = inject(TenantAiService);

    loadModels$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantAiActions.loadModels),
            switchMap(() =>
                this.tenantAiService.getModels().pipe(
                    map(models => TenantAiActions.loadModelsSuccess({models})),
                    catchError(error =>
                        of(
                            TenantAiActions.loadModelsFailure({
                                error: this.errors.of(error, "errors.tenantAi.loadModels"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    loadSettings$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantAiActions.loadSettings),
            switchMap(() =>
                this.tenantAiService.getSettings().pipe(
                    map(settings => TenantAiActions.loadSettingsSuccess({settings})),
                    catchError(error =>
                        of(
                            TenantAiActions.loadSettingsFailure({
                                error: this.errors.of(error, "errors.tenantAi.loadSettings"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    setEmbeddingModel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantAiActions.setEmbeddingModel),
            concatMap(({modelId}) =>
                this.tenantAiService.setEmbeddingModel(modelId).pipe(
                    map(settings => TenantAiActions.setEmbeddingModelSuccess({settings})),
                    catchError(error =>
                        of(
                            TenantAiActions.setEmbeddingModelFailure({
                                error: this.errors.of(error, "errors.tenantAi.setEmbeddingModel"),
                            }),
                        ),
                    ),
                ),
            ),
            // Re-embedding everything takes minutes: its answer must not reach the next login in this tab.
            endedByLogout(this.actions$),
        ),
    );

    setSearchLanguage$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantAiActions.setSearchLanguage),
            concatMap(({language}) =>
                this.tenantAiService.setSearchLanguage(language).pipe(
                    map(settings => TenantAiActions.setSearchLanguageSuccess({settings})),
                    catchError(error =>
                        of(
                            TenantAiActions.setSearchLanguageFailure({
                                error: this.errors.of(error, "errors.tenantAi.setSearchLanguage"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    setRerankingModel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantAiActions.setRerankingModel),
            concatMap(({modelId}) =>
                this.tenantAiService.setRerankingModel(modelId).pipe(
                    map(settings => TenantAiActions.setRerankingModelSuccess({settings})),
                    catchError(error =>
                        of(
                            TenantAiActions.setRerankingModelFailure({
                                error: this.errors.of(error, "errors.tenantAi.setRerankingModel"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );
}
