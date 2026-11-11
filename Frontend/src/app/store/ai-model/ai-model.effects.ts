import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {AiModelService} from "../../services/ai-model.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as AiModelActions from "./ai-model.actions";

@Injectable()
export class AiModelEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private aiModelService = inject(AiModelService);

    loadModels$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AiModelActions.loadModels),
            switchMap(() =>
                this.aiModelService.getAll().pipe(
                    map(models => AiModelActions.loadModelsSuccess({models})),
                    catchError(error =>
                        of(
                            AiModelActions.loadModelsFailure({
                                error: this.errors.of(error, "errors.aiModel.loadModels"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    createModel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AiModelActions.createModel),
            concatMap(({model}) =>
                this.aiModelService.create(model).pipe(
                    map(created => AiModelActions.createModelSuccess({model: created})),
                    catchError(error =>
                        of(
                            AiModelActions.createModelFailure({
                                error: this.errors.of(error, "errors.aiModel.createModel"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    updateModel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AiModelActions.updateModel),
            concatMap(({id, model}) =>
                this.aiModelService.update(id, model).pipe(
                    map(updated => AiModelActions.updateModelSuccess({model: updated})),
                    catchError(error =>
                        of(
                            AiModelActions.updateModelFailure({
                                error: this.errors.of(error, "errors.aiModel.updateModel"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    deleteModel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AiModelActions.deleteModel),
            concatMap(({id}) =>
                this.aiModelService.delete(id).pipe(
                    map(() => AiModelActions.deleteModelSuccess({id})),
                    catchError(error =>
                        of(
                            AiModelActions.deleteModelFailure({
                                error: this.errors.of(error, "errors.aiModel.deleteModel"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    // Setting "transcribes" on one model takes it from the previous one in the backend - the list shows that only reloaded.
    reloadAfterSave$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AiModelActions.createModelSuccess, AiModelActions.updateModelSuccess),
            map(() => AiModelActions.loadModels()),
        ),
    );
}
