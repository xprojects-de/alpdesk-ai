import {createAction, props} from "@ngrx/store";
import {Branding, TenantBranding, TenantBrandingRequest} from "../../models/branding.model";

/** The installation's look - loaded before the app is shown (app.config.ts). */
export const loadInstallationBranding = createAction("[Branding] Load Installation");
export const loadInstallationBrandingSuccess = createAction(
    "[Branding] Load Installation Success",
    props<{branding: Branding; logoUrl: string | null}>(),
);
export const loadInstallationBrandingFailure = createAction(
    "[Branding] Load Installation Failure",
    props<{error: string}>(),
);

/** A tenant's look on its own login page /login/<key> - cleared on /login, which shows none. */
export const loadLoginBranding = createAction("[Branding] Load Login", props<{loginKey: string}>());
export const loadLoginBrandingSuccess = createAction(
    "[Branding] Load Login Success",
    props<{loginKey: string; branding: Branding; logoUrl: string | null}>(),
);
export const loadLoginBrandingFailure = createAction(
    "[Branding] Load Login Failure",
    props<{error: string; unknownKey: boolean}>(),
);
export const clearLoginBranding = createAction("[Branding] Clear Login");

/** The tenant admin's own tenant's login page key - shown to hand the address on. */
export const loadOwnLoginKey = createAction("[Branding] Load Own Login Key");
export const loadOwnLoginKeySuccess = createAction(
    "[Branding] Load Own Login Key Success",
    props<{loginKey: string}>(),
);
export const loadOwnLoginKeyFailure = createAction("[Branding] Load Own Login Key Failure", props<{error: string}>());

/** The logged-in user's tenant's look - loaded by the tenant area. */
export const loadTenantBranding = createAction("[Branding] Load Tenant");
export const loadTenantBrandingSuccess = createAction(
    "[Branding] Load Tenant Success",
    props<{branding: Branding; logoUrl: string | null}>(),
);
export const loadTenantBrandingFailure = createAction("[Branding] Load Tenant Failure", props<{error: string}>());

/** A tenant's look as the admin keeps it (dialog in the tenant list). */
export const loadAdminBranding = createAction("[Branding] Load Admin", props<{tenantId: number}>());
export const loadAdminBrandingSuccess = createAction(
    "[Branding] Load Admin Success",
    props<{tenantId: number; branding: TenantBranding; logoUrl: string | null}>(),
);
export const loadAdminBrandingFailure = createAction("[Branding] Load Admin Failure", props<{error: string}>());

export const saveAdminBranding = createAction(
    "[Branding] Save Admin",
    props<{tenantId: number; request: TenantBrandingRequest}>(),
);
export const saveAdminBrandingSuccess = createAction(
    "[Branding] Save Admin Success",
    props<{tenantId: number; branding: TenantBranding}>(),
);
export const saveAdminBrandingFailure = createAction("[Branding] Save Admin Failure", props<{error: string}>());

export const renewAdminLoginKey = createAction("[Branding] Renew Admin Login Key", props<{tenantId: number}>());
export const renewAdminLoginKeySuccess = createAction(
    "[Branding] Renew Admin Login Key Success",
    props<{tenantId: number; branding: TenantBranding}>(),
);
export const renewAdminLoginKeyFailure = createAction(
    "[Branding] Renew Admin Login Key Failure",
    props<{error: string}>(),
);

export const uploadAdminLogo = createAction("[Branding] Upload Admin Logo", props<{tenantId: number; file: File}>());
export const uploadAdminLogoSuccess = createAction("[Branding] Upload Admin Logo Success", props<{tenantId: number}>());
export const uploadAdminLogoFailure = createAction("[Branding] Upload Admin Logo Failure", props<{error: string}>());

export const deleteAdminLogo = createAction("[Branding] Delete Admin Logo", props<{tenantId: number}>());
export const deleteAdminLogoSuccess = createAction("[Branding] Delete Admin Logo Success", props<{tenantId: number}>());
export const deleteAdminLogoFailure = createAction("[Branding] Delete Admin Logo Failure", props<{error: string}>());
