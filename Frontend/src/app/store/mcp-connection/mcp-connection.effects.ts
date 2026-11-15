import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, exhaustMap, filter, map, mergeMap, switchMap, tap} from "rxjs/operators";
import {McpConnectionService} from "../../services/mcp-connection.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {isWebAddress, leaveFor} from "../../utils/mcp-connection.util";
import * as AccountActions from "../account/account.actions";
import {endedByLogout} from "../session.operator";
import * as McpConnectionActions from "./mcp-connection.actions";

@Injectable()
export class McpConnectionEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private service = inject(McpConnectionService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.loadConnections),
            switchMap(({scope}) =>
                this.service.list(scope).pipe(
                    map(connections => McpConnectionActions.loadConnectionsSuccess({scope, connections})),
                    catchError(error =>
                        of(
                            McpConnectionActions.loadConnectionsFailure({
                                error: this.errors.of(error, "errors.mcpConnection.loadConnections"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    create$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.createConnection),
            concatMap(({scope, request}) =>
                this.service.create(scope, request).pipe(
                    map(connection => McpConnectionActions.createConnectionSuccess({scope, connection})),
                    catchError(error =>
                        of(
                            McpConnectionActions.createConnectionFailure({
                                error: this.errors.of(error, "errors.mcpConnection.createConnection"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    update$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.updateConnection),
            concatMap(({scope, connectionId, request}) =>
                this.service.update(scope, connectionId, request).pipe(
                    map(connection => McpConnectionActions.updateConnectionSuccess({scope, connection})),
                    catchError(error =>
                        of(
                            McpConnectionActions.updateConnectionFailure({
                                error: this.errors.of(error, "errors.mcpConnection.updateConnection"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    signIn$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.signIn),
            exhaustMap(({scope, connectionId, returnUrl}) =>
                this.service.signIn(scope, connectionId, returnUrl).pipe(
                    map(({authorizationUrl}) =>
                        isWebAddress(authorizationUrl)
                            ? McpConnectionActions.signInStarted({authorizationUrl})
                            : McpConnectionActions.signInFailure({
                                  error: this.errors.of(null, "errors.mcpConnection.signIn"),
                              }),
                    ),
                    // The refusal may leave what the dialog shows next (a server needing a client id by hand:
                    // its redirect address and the fields) - the connections are read again.
                    catchError(error =>
                        of(
                            McpConnectionActions.signInFailure({
                                error: this.errors.of(error, "errors.mcpConnection.signIn"),
                            }),
                            McpConnectionActions.loadConnections({scope}),
                        ),
                    ),
                ),
            ),
            // An answer arriving after a logout must not send the next person at this browser to the server's page.
            endedByLogout(this.actions$),
        ),
    );

    /** The connections are read after it either way: the page's own reading may have started before it was done. */
    completeSignIn$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.completeSignIn),
            exhaustMap(({scope, connectionId, callback}) =>
                this.service.completeSignIn(scope, connectionId, callback).pipe(
                    mergeMap(connection =>
                        of(
                            McpConnectionActions.completeSignInSuccess({scope, connection}),
                            McpConnectionActions.loadConnections({scope}),
                        ),
                    ),
                    catchError(error =>
                        of(
                            McpConnectionActions.completeSignInFailure({
                                error: this.errors.of(error, "errors.mcpConnection.completeSignIn"),
                            }),
                            McpConnectionActions.loadConnections({scope}),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    /** The server's page may refuse to run in a popup, and the way back is a redirect anyway: this tab goes there. */
    leaveForSignIn$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(McpConnectionActions.signInStarted),
                tap(({authorizationUrl}) => leaveFor(authorizationUrl)),
            ),
        {dispatch: false},
    );

    /**
     * Whether an agent answers the chat is part of the account, read once when the shell starts: a tenant admin who
     * connects, changes or removes software sees their own chat follow at once.
     */
    reloadAccountAfterChange$ = createEffect(() =>
        this.actions$.pipe(
            ofType(
                McpConnectionActions.createConnectionSuccess,
                McpConnectionActions.updateConnectionSuccess,
                McpConnectionActions.deleteConnectionSuccess,
            ),
            filter(({scope}) => scope === "self"),
            map(() => AccountActions.loadAccount()),
        ),
    );

    /** Without its sign-in the software is read again - it names its sign-in anew, so "Sign in" is offered again. */
    reloadAfterRemoveSignIn$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.removeSignInSuccess),
            map(({scope, connectionId}) => McpConnectionActions.reloadTools({scope, connectionId})),
        ),
    );

    removeSignIn$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.removeSignIn),
            concatMap(({scope, connectionId}) =>
                this.service.removeSignIn(scope, connectionId).pipe(
                    map(() => McpConnectionActions.removeSignInSuccess({scope, connectionId})),
                    catchError(error =>
                        of(
                            McpConnectionActions.removeSignInFailure({
                                error: this.errors.of(error, "errors.mcpConnection.removeSignIn"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.deleteConnection),
            concatMap(({scope, connectionId}) =>
                this.service.delete(scope, connectionId).pipe(
                    map(() => McpConnectionActions.deleteConnectionSuccess({scope, connectionId})),
                    catchError(error =>
                        of(
                            McpConnectionActions.deleteConnectionFailure({
                                error: this.errors.of(error, "errors.mcpConnection.deleteConnection"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    check$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.checkConnection),
            concatMap(({scope, connectionId}) =>
                this.service.check(scope, connectionId).pipe(
                    map(check => McpConnectionActions.checkConnectionSuccess({scope, connectionId, check})),
                    catchError(error =>
                        of(
                            McpConnectionActions.checkConnectionFailure({
                                connectionId,
                                error: this.errors.of(error, "errors.mcpConnection.check"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    reload$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.reloadTools),
            concatMap(({scope, connectionId}) =>
                this.service.reload(scope, connectionId).pipe(
                    map(connection => McpConnectionActions.reloadToolsSuccess({scope, connection})),
                    catchError(error =>
                        of(
                            McpConnectionActions.reloadToolsFailure({
                                connectionId,
                                error: this.errors.of(error, "errors.mcpConnection.reloadTools"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    updateTool$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.updateTool),
            // concatMap: two switches of the same tool in one round trip must reach the backend in their order.
            concatMap(({scope, connectionId, toolId, change}) =>
                this.service.updateTool(scope, connectionId, toolId, change).pipe(
                    map(tool => McpConnectionActions.updateToolSuccess({scope, connectionId, tool})),
                    catchError(error =>
                        of(
                            McpConnectionActions.updateToolFailure({
                                error: this.errors.of(error, "errors.mcpConnection.updateTool"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    updatePrompt$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.updatePrompt),
            // concatMap: two switches of the same prompt in one round trip must reach the backend in their order.
            concatMap(({scope, connectionId, promptId, enabled}) =>
                this.service.updatePrompt(scope, connectionId, promptId, enabled).pipe(
                    map(prompt => McpConnectionActions.updatePromptSuccess({scope, connectionId, prompt})),
                    catchError(error =>
                        of(
                            McpConnectionActions.updatePromptFailure({
                                error: this.errors.of(error, "errors.mcpConnection.updatePrompt"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    setPassThrough$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.setPassThrough),
            concatMap(({scope, connectionId, passesThrough}) =>
                this.service.setPassThrough(scope, connectionId, passesThrough).pipe(
                    map(connection => McpConnectionActions.setPassThroughSuccess({scope, connection})),
                    catchError(error =>
                        of(
                            McpConnectionActions.setPassThroughFailure({
                                error: this.errors.of(error, "errors.mcpConnection.setPassThrough"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    setPassResources$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.setPassResources),
            concatMap(({scope, connectionId, passesResources}) =>
                this.service.setPassResources(scope, connectionId, passesResources).pipe(
                    map(connection => McpConnectionActions.setPassResourcesSuccess({scope, connection})),
                    catchError(error =>
                        of(
                            McpConnectionActions.setPassResourcesFailure({
                                error: this.errors.of(error, "errors.mcpConnection.setPassResources"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    loadAccess$ = createEffect(() =>
        this.actions$.pipe(
            ofType(McpConnectionActions.loadAccess),
            mergeMap(({scope, connectionId}) =>
                this.service.access(scope, connectionId).pipe(
                    map(access => McpConnectionActions.loadAccessSuccess({scope, connectionId, access})),
                    catchError(error =>
                        of(
                            McpConnectionActions.loadAccessFailure({
                                error: this.errors.of(error, "errors.mcpConnection.loadAccess"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
