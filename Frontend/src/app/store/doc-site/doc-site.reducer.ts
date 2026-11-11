import {createReducer, on} from "@ngrx/store";
import {DocSite, OpenedDocSite} from "../../models/doc-site.model";
import {TenantScope} from "../../models/tenant.model";
import {sortSites} from "../../utils/doc-site.util";
import * as SiteActions from "./doc-site.actions";

/**
 * The websites one tenant's admins keep (scope: the tenant admin's own or the tenant the admin opened), the signed-in
 * user's handbook, the largest ZIP the backend takes (null until the websites are loaded - the backend checks anyway), the handbook's website open right now and the website previewed by the admins. handbookFailed and
 * openFailed: the handbook's list or the address of its website could not be fetched - the page offers to try again
 * instead of loading for ever.
 */
export interface DocSiteState {
    scope: TenantScope | null;
    sites: DocSite[];
    loading: boolean;
    uploading: boolean;
    maxZipBytes: number | null;
    handbook: DocSite[];
    handbookLoaded: boolean;
    handbookFailed: boolean;
    opened: OpenedDocSite | null;
    openFailed: boolean;
    preview: OpenedDocSite | null;
}

export const initialState: DocSiteState = {
    scope: null,
    sites: [],
    loading: false,
    uploading: false,
    maxZipBytes: null,
    handbook: [],
    handbookLoaded: false,
    handbookFailed: false,
    opened: null,
    openFailed: false,
    preview: null,
};

/** The handbook's address (scope null) and a preview's apart: closing a preview must not empty the handbook. */
function withAddress(state: DocSiteState, scope: TenantScope | null, address: OpenedDocSite | null): DocSiteState {
    return scope === null ? {...state, opened: address} : {...state, preview: address};
}

export const docSiteReducer = createReducer(
    initialState,

    on(SiteActions.loadSites, (state, {scope}) => ({
        ...state,
        scope,
        sites: state.scope === scope ? state.sites : [],
        loading: true,
    })),
    on(SiteActions.loadSitesSuccess, (state, {scope, sites, maxZipBytes}) =>
        state.scope === scope ? {...state, sites: sortSites(sites), maxZipBytes, loading: false} : state,
    ),
    on(SiteActions.loadSitesFailure, state => ({...state, loading: false})),

    on(SiteActions.uploadSite, state => ({...state, uploading: true})),
    on(SiteActions.uploadSiteSuccess, (state, {scope, upload}) =>
        state.scope === scope
            ? {
                  ...state,
                  uploading: false,
                  sites: sortSites([...state.sites.filter(site => site.id !== upload.site.id), upload.site]),
              }
            : {...state, uploading: false},
    ),
    on(SiteActions.uploadSiteFailure, state => ({...state, uploading: false})),

    on(SiteActions.deleteSiteSuccess, (state, {scope, id}) =>
        state.scope === scope ? {...state, sites: state.sites.filter(site => site.id !== id)} : state,
    ),

    on(SiteActions.loadHandbook, state => ({...state, handbookFailed: false})),
    on(SiteActions.loadHandbookSuccess, (state, {sites}) => ({
        ...state,
        handbook: sortSites(sites),
        handbookLoaded: true,
    })),
    on(SiteActions.loadHandbookFailure, state => ({...state, handbookLoaded: true, handbookFailed: true})),

    on(SiteActions.openSite, (state, {scope}) => ({
        ...withAddress(state, scope, null),
        openFailed: scope === null ? false : state.openFailed,
    })),
    on(SiteActions.openSiteSuccess, (state, {scope, siteId, url}) => withAddress(state, scope, {siteId, url})),
    on(SiteActions.openSiteFailure, (state, {scope}) => (scope === null ? {...state, openFailed: true} : state)),
    on(SiteActions.closeSite, (state, {scope}) => withAddress(state, scope, null)),
);
