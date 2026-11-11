import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {forkJoin, of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {AssistantService} from "../../services/assistant.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as AssistantActions from "./assistant.actions";

@Injectable()
export class AssistantEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private assistantService = inject(AssistantService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AssistantActions.loadAssistant),
            switchMap(({scope}) =>
                forkJoin({
                    settings: this.assistantService.getSettings(scope),
                    templates: this.assistantService.getTemplates(scope),
                    models: this.assistantService.getModels(scope),
                    imageModels: this.assistantService.getImageModels(scope),
                }).pipe(
                    map(({settings, templates, models, imageModels}) =>
                        AssistantActions.loadAssistantSuccess({scope, settings, templates, models, imageModels}),
                    ),
                    catchError(error =>
                        of(
                            AssistantActions.loadAssistantFailure({
                                error: this.errors.of(error, "errors.assistant.loadAssistant"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    saveSettings$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AssistantActions.saveSettings),
            concatMap(({scope, settings}) =>
                this.assistantService.saveSettings(scope, settings).pipe(
                    map(saved => AssistantActions.saveSettingsSuccess({scope, settings: saved})),
                    catchError(error =>
                        of(
                            AssistantActions.saveSettingsFailure({
                                error: this.errors.of(error, "errors.assistant.saveSettings"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    createTemplate$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AssistantActions.createTemplate),
            concatMap(({scope, template}) =>
                this.assistantService.createTemplate(scope, template).pipe(
                    map(created => AssistantActions.createTemplateSuccess({scope, template: created})),
                    catchError(error =>
                        of(
                            AssistantActions.createTemplateFailure({
                                error: this.errors.of(error, "errors.assistant.createTemplate"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    updateTemplate$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AssistantActions.updateTemplate),
            concatMap(({scope, id, template}) =>
                this.assistantService.updateTemplate(scope, id, template).pipe(
                    map(updated => AssistantActions.updateTemplateSuccess({scope, template: updated})),
                    catchError(error =>
                        of(
                            AssistantActions.updateTemplateFailure({
                                error: this.errors.of(error, "errors.assistant.updateTemplate"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    deleteTemplate$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AssistantActions.deleteTemplate),
            concatMap(({scope, id}) =>
                this.assistantService.deleteTemplate(scope, id).pipe(
                    map(() => AssistantActions.deleteTemplateSuccess({scope, id})),
                    catchError(error =>
                        of(
                            AssistantActions.deleteTemplateFailure({
                                error: this.errors.of(error, "errors.assistant.deleteTemplate"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
