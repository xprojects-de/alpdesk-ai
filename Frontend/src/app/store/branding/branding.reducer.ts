import {createReducer, on} from "@ngrx/store";
import {Branding, TenantBranding} from "../../models/branding.model";
import * as AuthActions from "../auth/auth.actions";
import * as BrandingActions from "./branding.actions";

/**
 * The installation's look and a tenant's login page's (both anonymous, kept across logouts - a logout leads back to
 * that login page), the logged-in user's tenant's (dropped at logout), the tenant the admin edits and the tenant
 * admin's own login page key. Logo URLs are object URLs of blobs, except the anonymous ones.
 */
export interface BrandingState {
    installation: Branding | null;
    installationLogoUrl: string | null;
    login: Branding | null;
    loginLogoUrl: string | null;
    ownLoginKey: string | null;
    tenant: Branding | null;
    tenantLogoUrl: string | null;
    admin: {tenantId: number | null; branding: TenantBranding | null; logoUrl: string | null; loading: boolean};
}

export const initialState: BrandingState = {
    installation: null,
    installationLogoUrl: null,
    login: null,
    loginLogoUrl: null,
    ownLoginKey: null,
    tenant: null,
    tenantLogoUrl: null,
    admin: {tenantId: null, branding: null, logoUrl: null, loading: false},
};

export const brandingReducer = createReducer(
    initialState,

    on(BrandingActions.loadInstallationBrandingSuccess, (state, {branding, logoUrl}) => ({
        ...state,
        installation: branding,
        installationLogoUrl: logoUrl,
    })),
    on(BrandingActions.loadLoginBrandingSuccess, (state, {branding, logoUrl}) => ({
        ...state,
        login: branding,
        loginLogoUrl: logoUrl,
    })),
    on(BrandingActions.loadLoginBrandingFailure, BrandingActions.clearLoginBranding, state => ({
        ...state,
        login: null,
        loginLogoUrl: null,
    })),
    on(BrandingActions.loadOwnLoginKeySuccess, (state, {loginKey}) => ({...state, ownLoginKey: loginKey})),
    on(BrandingActions.loadTenantBrandingSuccess, (state, {branding, logoUrl}) => ({
        ...state,
        tenant: branding,
        tenantLogoUrl: logoUrl,
    })),
    on(AuthActions.logoutSuccess, state => ({
        ...initialState,
        installation: state.installation,
        installationLogoUrl: state.installationLogoUrl,
        login: state.login,
        loginLogoUrl: state.loginLogoUrl,
    })),

    on(BrandingActions.loadAdminBranding, (state, {tenantId}) => ({
        ...state,
        admin: {
            tenantId,
            branding: state.admin.tenantId === tenantId ? state.admin.branding : null,
            logoUrl: state.admin.tenantId === tenantId ? state.admin.logoUrl : null,
            loading: true,
        },
    })),
    on(
        BrandingActions.saveAdminBranding,
        BrandingActions.uploadAdminLogo,
        BrandingActions.deleteAdminLogo,
        BrandingActions.renewAdminLoginKey,
        state => ({...state, admin: {...state.admin, loading: true}}),
    ),
    on(
        BrandingActions.loadAdminBrandingFailure,
        BrandingActions.saveAdminBrandingFailure,
        BrandingActions.renewAdminLoginKeyFailure,
        BrandingActions.uploadAdminLogoFailure,
        BrandingActions.deleteAdminLogoFailure,
        state => ({...state, admin: {...state.admin, loading: false}}),
    ),
    // Results for another tenant (its dialog was closed and another one opened meanwhile) are ignored.
    on(BrandingActions.loadAdminBrandingSuccess, (state, {tenantId, branding, logoUrl}) =>
        state.admin.tenantId === tenantId ? {...state, admin: {tenantId, branding, logoUrl, loading: false}} : state,
    ),
    on(
        BrandingActions.saveAdminBrandingSuccess,
        BrandingActions.renewAdminLoginKeySuccess,
        (state, {tenantId, branding}) =>
            state.admin.tenantId === tenantId ? {...state, admin: {...state.admin, branding, loading: false}} : state,
    ),
);
