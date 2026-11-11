import {createFeatureSelector, createSelector} from "@ngrx/store";
import {DEFAULT_BRANDING, NEUTRAL_BRANDING} from "../../models/branding.model";
import {selectIsAuthenticated} from "../auth/auth.selectors";
import {BrandingState} from "./branding.reducer";

export const selectBrandingState = createFeatureSelector<BrandingState>("branding");

/**
 * What is shown: before the login the tenant's look on its own login page, else nothing of a brand (/login serves
 * every tenant); after it the tenant's look in the tenant area, else the installation's, else the build's own.
 */
export const selectBranding = createSelector(selectBrandingState, selectIsAuthenticated, (state, authenticated) =>
    authenticated ? (state.tenant ?? state.installation ?? DEFAULT_BRANDING) : (state.login ?? NEUTRAL_BRANDING),
);

export const selectLogoUrl = createSelector(selectBrandingState, selectIsAuthenticated, (state, authenticated) => {
    if (!authenticated) {
        return state.loginLogoUrl;
    }
    return state.tenant ? state.tenantLogoUrl : state.installationLogoUrl;
});

/** The look of the tenant's own login page being shown; null on /login. */
export const selectLoginBranding = createSelector(selectBrandingState, state => state.login);

export const selectLoginLogoUrl = createSelector(selectBrandingState, state => state.loginLogoUrl);

export const selectOwnLoginKey = createSelector(selectBrandingState, state => state.ownLoginKey);

/** The installation's name - for texts in the admin's area, which shows no tenant's look. */
export const selectInstallationName = createSelector(
    selectBrandingState,
    state => (state.installation ?? DEFAULT_BRANDING).name,
);

export const selectAdminBranding = createSelector(selectBrandingState, state => state.admin.branding);

export const selectAdminLogoUrl = createSelector(selectBrandingState, state => state.admin.logoUrl);

export const selectAdminBrandingLoading = createSelector(selectBrandingState, state => state.admin.loading);
