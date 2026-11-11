import {inject} from "@angular/core";
import {Router, CanActivateFn} from "@angular/router";
import {Store} from "@ngrx/store";
import {map, take} from "rxjs/operators";
import {ROLE_ADMIN, ROLE_TENANT_ADMIN, ROLE_TENANT_USER} from "../models/auth.model";
import {homePathFor} from "../utils/auth.util";
import {localStorageOf} from "../utils/color-scheme.util";
import {loginPath, readLoginKey} from "../utils/login-page.util";
import * as AuthSelectors from "../store/auth/auth.selectors";
import * as AuthActions from "../store/auth/auth.actions";

/** Lets only logins with `role` in; any other login goes to its own area, no login to /login. */
function roleGuard(role: string): CanActivateFn {
    return () => {
        const store = inject(Store);
        const router = inject(Router);
        store.dispatch(AuthActions.checkAuth());

        return store.select(AuthSelectors.selectAuthState).pipe(
            take(1),
            map(({isAuthenticated, roles}) => {
                if (!isAuthenticated) {
                    return router.createUrlTree(loginPath(readLoginKey(localStorageOf(window))));
                }
                if (roles.includes(role)) {
                    return true;
                }
                return router.createUrlTree([homePathFor(roles) ?? "/login"]);
            }),
        );
    };
}

export const adminGuard = roleGuard(ROLE_ADMIN);

export const tenantUserGuard = roleGuard(ROLE_TENANT_USER);

/** The tenant area's settings pages: a user who is no tenant admin goes to the chat. */
export const tenantAdminGuard = roleGuard(ROLE_TENANT_ADMIN);
