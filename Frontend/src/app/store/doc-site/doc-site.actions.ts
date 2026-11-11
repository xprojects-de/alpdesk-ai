import {createAction, props} from "@ngrx/store";
import {DocSite, DocSiteUpload} from "../../models/doc-site.model";
import {TenantScope} from "../../models/tenant.model";

/** The websites a tenant's admins keep - the tenant admin's own tenant, or the tenant the admin opened. */
export const loadSites = createAction("[Doc Site] Load", props<{scope: TenantScope}>());
export const loadSitesSuccess = createAction(
    "[Doc Site] Load Success",
    props<{scope: TenantScope; sites: DocSite[]; maxZipBytes: number}>(),
);
export const loadSitesFailure = createAction("[Doc Site] Load Failure", props<{error: string}>());

export const uploadSite = createAction(
    "[Doc Site] Upload",
    props<{scope: TenantScope; file: File; areaId: number | null}>(),
);
export const uploadSiteSuccess = createAction(
    "[Doc Site] Upload Success",
    props<{scope: TenantScope; upload: DocSiteUpload}>(),
);
export const uploadSiteFailure = createAction("[Doc Site] Upload Failure", props<{error: string}>());

export const deleteSite = createAction("[Doc Site] Delete", props<{scope: TenantScope; id: number}>());
export const deleteSiteSuccess = createAction("[Doc Site] Delete Success", props<{scope: TenantScope; id: number}>());
export const deleteSiteFailure = createAction("[Doc Site] Delete Failure", props<{error: string}>());

/** The websites the signed-in tenant user reads - its handbook. */
export const loadHandbook = createAction("[Doc Site] Load Handbook");
export const loadHandbookSuccess = createAction("[Doc Site] Load Handbook Success", props<{sites: DocSite[]}>());
export const loadHandbookFailure = createAction("[Doc Site] Load Handbook Failure", props<{error: string}>());

/**
 * A website to read now: scope null as a tenant user (the handbook), else for the tenant's admins (a preview). The two
 * keep their own address - a tenant admin previews a website on the handbook's page.
 */
export const openSite = createAction("[Doc Site] Open", props<{scope: TenantScope | null; id: number}>());
export const openSiteSuccess = createAction(
    "[Doc Site] Open Success",
    props<{scope: TenantScope | null; siteId: number; url: string}>(),
);
export const openSiteFailure = createAction(
    "[Doc Site] Open Failure",
    props<{scope: TenantScope | null; error: string}>(),
);
export const closeSite = createAction("[Doc Site] Close", props<{scope: TenantScope | null}>());
