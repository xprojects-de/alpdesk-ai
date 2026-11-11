import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {ProviderKeyService} from "../../services/provider-key.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as ProviderKeyActions from "./provider-key.actions";

@Injectable()
export class ProviderKeyEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private providerKeyService = inject(ProviderKeyService);

    loadKeys$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ProviderKeyActions.loadKeys),
            switchMap(({scope}) =>
                this.providerKeyService.getAll(scope).pipe(
                    map(keys => ProviderKeyActions.loadKeysSuccess({scope, keys})),
                    catchError(error =>
                        of(
                            ProviderKeyActions.loadKeysFailure({
                                error: this.errors.of(error, "errors.providerKey.loadKeys"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    setKey$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ProviderKeyActions.setKey),
            concatMap(({scope, provider, apiKey}) =>
                this.providerKeyService.set(scope, provider, apiKey).pipe(
                    map(key => ProviderKeyActions.setKeySuccess({scope, key})),
                    catchError(error =>
                        of(
                            ProviderKeyActions.setKeyFailure({
                                error: this.errors.of(error, "errors.providerKey.setKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    removeKey$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ProviderKeyActions.removeKey),
            concatMap(({scope, provider}) =>
                this.providerKeyService.remove(scope, provider).pipe(
                    map(() => ProviderKeyActions.removeKeySuccess({scope, provider})),
                    catchError(error =>
                        of(
                            ProviderKeyActions.removeKeyFailure({
                                error: this.errors.of(error, "errors.providerKey.removeKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
