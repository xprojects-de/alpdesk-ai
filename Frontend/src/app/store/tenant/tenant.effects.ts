import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {TenantService} from "../../services/tenant.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as TenantActions from "./tenant.actions";

@Injectable()
export class TenantEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private tenantService = inject(TenantService);

    loadTenants$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.loadTenants),
            switchMap(() =>
                this.tenantService.getAll().pipe(
                    map(tenants => TenantActions.loadTenantsSuccess({tenants})),
                    catchError(error =>
                        of(
                            TenantActions.loadTenantsFailure({
                                error: this.errors.of(error, "errors.tenant.loadTenants"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    createTenant$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.createTenant),
            concatMap(({tenant}) =>
                this.tenantService.create(tenant).pipe(
                    map(created => TenantActions.createTenantSuccess({tenant: created})),
                    catchError(error =>
                        of(
                            TenantActions.createTenantFailure({
                                error: this.errors.of(error, "errors.tenant.createTenant"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    updateTenant$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.updateTenant),
            concatMap(({id, tenant}) =>
                this.tenantService.update(id, tenant).pipe(
                    map(updated => TenantActions.updateTenantSuccess({tenant: updated})),
                    catchError(error =>
                        of(
                            TenantActions.updateTenantFailure({
                                error: this.errors.of(error, "errors.tenant.updateTenant"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    checkSandbox$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.checkSandbox),
            switchMap(({tenantId}) =>
                this.tenantService.checkSandbox(tenantId).pipe(
                    map(({instances, extrasAgree}) =>
                        TenantActions.checkSandboxSuccess({tenantId, instances, extrasAgree}),
                    ),
                    catchError(error =>
                        of(
                            TenantActions.checkSandboxFailure({
                                error: this.errors.of(error, "errors.tenant.checkSandbox"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    deleteTenant$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.deleteTenant),
            concatMap(({id}) =>
                this.tenantService.delete(id).pipe(
                    map(() => TenantActions.deleteTenantSuccess({id})),
                    catchError(error =>
                        of(
                            TenantActions.deleteTenantFailure({
                                error: this.errors.of(error, "errors.tenant.deleteTenant"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    setEmbeddingModel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.setEmbeddingModel),
            concatMap(({tenantId, modelId}) =>
                this.tenantService.setEmbeddingModel(tenantId, modelId).pipe(
                    map(tenant => TenantActions.setEmbeddingModelSuccess({tenant})),
                    catchError(error =>
                        of(
                            TenantActions.setEmbeddingModelFailure({
                                error: this.errors.of(error, "errors.tenant.setEmbeddingModel"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    setRerankingModel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.setRerankingModel),
            concatMap(({tenantId, modelId}) =>
                this.tenantService.setRerankingModel(tenantId, modelId).pipe(
                    map(tenant => TenantActions.setRerankingModelSuccess({tenant})),
                    catchError(error =>
                        of(
                            TenantActions.setRerankingModelFailure({
                                error: this.errors.of(error, "errors.tenant.setRerankingModel"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    setSearchLanguage$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.setSearchLanguage),
            concatMap(({tenantId, language}) =>
                this.tenantService.setSearchLanguage(tenantId, language).pipe(
                    map(tenant => TenantActions.setSearchLanguageSuccess({tenant})),
                    catchError(error =>
                        of(
                            TenantActions.setSearchLanguageFailure({
                                error: this.errors.of(error, "errors.tenant.setSearchLanguage"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    loadApiKeys$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.loadApiKeys),
            switchMap(({scope}) =>
                this.tenantService.getApiKeys(scope).pipe(
                    map(keys => TenantActions.loadApiKeysSuccess({scope, keys})),
                    catchError(error =>
                        of(
                            TenantActions.loadApiKeysFailure({
                                error: this.errors.of(error, "errors.tenant.loadApiKeys"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    createApiKey$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.createApiKey),
            concatMap(({scope, request}) =>
                this.tenantService.createApiKey(scope, request).pipe(
                    map(({key, plainKey}) => TenantActions.createApiKeySuccess({scope, key, plainKey})),
                    catchError(error =>
                        of(
                            TenantActions.createApiKeyFailure({
                                error: this.errors.of(error, "errors.tenant.createApiKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    updateApiKey$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.updateApiKey),
            concatMap(({scope, keyId, request}) =>
                this.tenantService.updateApiKey(scope, keyId, request).pipe(
                    map(key => TenantActions.updateApiKeySuccess({scope, key})),
                    catchError(error =>
                        of(
                            TenantActions.updateApiKeyFailure({
                                error: this.errors.of(error, "errors.tenant.updateApiKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    revokeApiKey$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantActions.revokeApiKey),
            concatMap(({scope, keyId}) =>
                this.tenantService.revokeApiKey(scope, keyId).pipe(
                    map(() => TenantActions.revokeApiKeySuccess({scope, keyId})),
                    catchError(error =>
                        of(
                            TenantActions.revokeApiKeyFailure({
                                error: this.errors.of(error, "errors.tenant.revokeApiKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
