import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {KnowledgeAreaService} from "../../services/knowledge-area.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as AreaActions from "./knowledge-area.actions";

@Injectable()
export class KnowledgeAreaEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private areaService = inject(KnowledgeAreaService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AreaActions.loadAreas),
            switchMap(({scope}) =>
                this.areaService.getAll(scope).pipe(
                    map(areas => AreaActions.loadAreasSuccess({scope, areas})),
                    catchError(error =>
                        of(
                            AreaActions.loadAreasFailure({
                                error: this.errors.of(error, "errors.knowledgeArea.loadAreas"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    create$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AreaActions.createArea),
            concatMap(({scope, name}) =>
                this.areaService.create(scope, name).pipe(
                    map(area => AreaActions.createAreaSuccess({scope, area})),
                    catchError(error =>
                        of(
                            AreaActions.createAreaFailure({
                                error: this.errors.of(error, "errors.knowledgeArea.createArea"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    rename$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AreaActions.renameArea),
            concatMap(({scope, id, name}) =>
                this.areaService.rename(scope, id, name).pipe(
                    map(area => AreaActions.renameAreaSuccess({scope, area})),
                    catchError(error =>
                        of(
                            AreaActions.renameAreaFailure({
                                error: this.errors.of(error, "errors.knowledgeArea.renameArea"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AreaActions.deleteArea),
            concatMap(({scope, id}) =>
                this.areaService.delete(scope, id).pipe(
                    map(() => AreaActions.deleteAreaSuccess({scope, id})),
                    catchError(error =>
                        of(
                            AreaActions.deleteAreaFailure({
                                error: this.errors.of(error, "errors.knowledgeArea.deleteArea"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
