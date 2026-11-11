import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {McpKeyService} from "../../services/mcp-key.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as McpKeyActions from "./mcp-key.actions";

@Injectable()
export class McpKeyEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private mcpKeyService = inject(McpKeyService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpKeyActions.loadMcpKeys),
            switchMap(({scope}) =>
                this.mcpKeyService.list(scope).pipe(
                    map(keys => McpKeyActions.loadMcpKeysSuccess({scope, keys})),
                    catchError(error =>
                        of(
                            McpKeyActions.loadMcpKeysFailure({
                                error: this.errors.of(error, "errors.mcpKey.loadMcpKeys"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    create$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpKeyActions.createMcpKey),
            concatMap(({scope, request}) =>
                this.mcpKeyService.create(scope, request).pipe(
                    map(created =>
                        McpKeyActions.createMcpKeySuccess({scope, key: created.key, plainKey: created.plainKey}),
                    ),
                    catchError(error =>
                        of(
                            McpKeyActions.createMcpKeyFailure({
                                error: this.errors.of(error, "errors.mcpKey.createMcpKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    update$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpKeyActions.updateMcpKey),
            concatMap(({scope, keyId, request}) =>
                this.mcpKeyService.update(scope, keyId, request).pipe(
                    map(key => McpKeyActions.updateMcpKeySuccess({scope, key})),
                    catchError(error =>
                        of(
                            McpKeyActions.updateMcpKeyFailure({
                                error: this.errors.of(error, "errors.mcpKey.updateMcpKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    renew$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpKeyActions.renewMcpKey),
            concatMap(({scope, keyId}) =>
                this.mcpKeyService.renew(scope, keyId).pipe(
                    map(renewed =>
                        McpKeyActions.renewMcpKeySuccess({scope, key: renewed.key, plainKey: renewed.plainKey}),
                    ),
                    catchError(error =>
                        of(
                            McpKeyActions.renewMcpKeyFailure({
                                error: this.errors.of(error, "errors.mcpKey.renewMcpKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpKeyActions.deleteMcpKey),
            concatMap(({scope, keyId}) =>
                this.mcpKeyService.delete(scope, keyId).pipe(
                    map(() => McpKeyActions.deleteMcpKeySuccess({scope, keyId})),
                    catchError(error =>
                        of(
                            McpKeyActions.deleteMcpKeyFailure({
                                error: this.errors.of(error, "errors.mcpKey.deleteMcpKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
