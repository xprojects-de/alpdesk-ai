import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {TenantUserService} from "../../services/tenant-user.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as TenantUserActions from "./tenant-user.actions";

@Injectable()
export class TenantUserEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private tenantUserService = inject(TenantUserService);

    loadUsers$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantUserActions.loadUsers),
            switchMap(({scope}) =>
                this.tenantUserService.getAll(scope).pipe(
                    map(users => TenantUserActions.loadUsersSuccess({scope, users})),
                    catchError(error =>
                        of(
                            TenantUserActions.loadUsersFailure({
                                error: this.errors.of(error, "errors.tenantUser.loadUsers"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    createUser$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantUserActions.createUser),
            concatMap(({scope, user}) =>
                this.tenantUserService.create(scope, user).pipe(
                    map(created => TenantUserActions.createUserSuccess({scope, user: created})),
                    catchError(error =>
                        of(
                            TenantUserActions.createUserFailure({
                                error: this.errors.of(error, "errors.tenantUser.createUser"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    addUserFromTenant$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantUserActions.addUserFromTenant),
            concatMap(({tenantId, request}) =>
                this.tenantUserService.addFromTenant(tenantId, request).pipe(
                    map(created => TenantUserActions.createUserSuccess({scope: tenantId, user: created})),
                    catchError(error =>
                        of(
                            TenantUserActions.createUserFailure({
                                error: this.errors.of(error, "errors.tenantUser.addFromTenant"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    loadOtherTenantUsers$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantUserActions.loadOtherTenantUsers),
            switchMap(({tenantId}) =>
                this.tenantUserService.getAll(tenantId).pipe(
                    map(users => TenantUserActions.loadOtherTenantUsersSuccess({tenantId, users})),
                    catchError(error =>
                        of(
                            TenantUserActions.loadOtherTenantUsersFailure({
                                error: this.errors.of(error, "errors.tenantUser.loadUsers"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    updateUser$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantUserActions.updateUser),
            concatMap(({scope, userId, user}) =>
                this.tenantUserService.update(scope, userId, user).pipe(
                    map(updated => TenantUserActions.updateUserSuccess({scope, user: updated})),
                    catchError(error =>
                        of(
                            TenantUserActions.updateUserFailure({
                                error: this.errors.of(error, "errors.tenantUser.updateUser"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    deleteUser$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantUserActions.deleteUser),
            concatMap(({scope, userId}) =>
                this.tenantUserService.delete(scope, userId).pipe(
                    map(() => TenantUserActions.deleteUserSuccess({scope, userId})),
                    catchError(error =>
                        of(
                            TenantUserActions.deleteUserFailure({
                                error: this.errors.of(error, "errors.tenantUser.deleteUser"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
