import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {AccountService} from "../../services/account.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as AccountActions from "./account.actions";

@Injectable()
export class AccountEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private accountService = inject(AccountService);

    loadAccount$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AccountActions.loadAccount),
            switchMap(() =>
                this.accountService.get().pipe(
                    map(account => AccountActions.loadAccountSuccess({account})),
                    catchError(error =>
                        of(
                            AccountActions.loadAccountFailure({
                                error: this.errors.of(error, "errors.account.loadAccount"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    changePassword$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AccountActions.changePassword),
            concatMap(({request}) =>
                this.accountService.changePassword(request).pipe(
                    map(() => AccountActions.changePasswordSuccess()),
                    catchError(error =>
                        of(
                            AccountActions.changePasswordFailure({
                                error: this.errors.of(error, "errors.account.changePassword"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
