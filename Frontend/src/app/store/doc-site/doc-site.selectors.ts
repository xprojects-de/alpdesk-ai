import {createFeatureSelector, createSelector} from "@ngrx/store";
import {DocSiteState} from "./doc-site.reducer";

export const selectDocSiteState = createFeatureSelector<DocSiteState>("docSite");

export const selectSites = createSelector(selectDocSiteState, state => state.sites);

export const selectSitesLoading = createSelector(selectDocSiteState, state => state.loading);

export const selectUploading = createSelector(selectDocSiteState, state => state.uploading);

export const selectMaxZipBytes = createSelector(selectDocSiteState, state => state.maxZipBytes);

export const selectHandbook = createSelector(selectDocSiteState, state => state.handbook);

export const selectHandbookLoaded = createSelector(selectDocSiteState, state => state.handbookLoaded);

export const selectHandbookFailed = createSelector(selectDocSiteState, state => state.handbookFailed);

export const selectOpenedSite = createSelector(selectDocSiteState, state => state.opened);

export const selectOpenFailed = createSelector(selectDocSiteState, state => state.openFailed);

export const selectPreviewSite = createSelector(selectDocSiteState, state => state.preview);
