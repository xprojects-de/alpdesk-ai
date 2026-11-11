import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {TenantModelService} from "../../services/tenant-model.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as TenantModelActions from "./tenant-model.actions";

@Injectable()
export class TenantModelEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private tenantModelService = inject(TenantModelService);

    loadTenantModels$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantModelActions.loadTenantModels),
            switchMap(({tenantId}) =>
                this.tenantModelService.getAssigned(tenantId).pipe(
                    map(modelIds => TenantModelActions.loadTenantModelsSuccess({tenantId, modelIds})),
                    catchError(error =>
                        of(
                            TenantModelActions.loadTenantModelsFailure({
                                error: this.errors.of(error, "errors.tenantModel.loadTenantModels"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    assignTenantModels$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantModelActions.assignTenantModels),
            concatMap(({tenantId, modelIds}) =>
                this.tenantModelService.assign(tenantId, modelIds).pipe(
                    map(assigned => TenantModelActions.assignTenantModelsSuccess({tenantId, modelIds: assigned})),
                    catchError(error =>
                        of(
                            TenantModelActions.assignTenantModelsFailure({
                                error: this.errors.of(error, "errors.tenantModel.assignTenantModels"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
