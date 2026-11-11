import {DocSite} from "../../models/doc-site.model";
import * as SiteActions from "./doc-site.actions";
import {docSiteReducer, initialState} from "./doc-site.reducer";

describe("docSiteReducer", () => {
    const everyone: DocSite = {id: 1, areaId: null, areaName: null, fileCount: 3, sizeBytes: 900, uploadedAt: ""};
    const sales: DocSite = {id: 2, areaId: 7, areaName: "Vertrieb", fileCount: 1, sizeBytes: 90, uploadedAt: ""};

    it("keeps one website per scope as they are uploaded, replaced and removed", () => {
        let state = docSiteReducer(initialState, SiteActions.loadSites({scope: 1}));
        state = docSiteReducer(state, SiteActions.loadSitesSuccess({scope: 1, sites: [sales], maxZipBytes: 2048}));
        expect(state.maxZipBytes).toBe(2048);
        state = docSiteReducer(state, SiteActions.uploadSite({scope: 1, file: new File([], "a.zip"), areaId: null}));
        expect(state.uploading).toBe(true);

        state = docSiteReducer(state, SiteActions.uploadSiteSuccess({scope: 1, upload: {site: everyone, skipped: []}}));
        expect(state.uploading).toBe(false);
        expect(state.sites.map(site => site.id)).toEqual([1, 2]);

        const replaced = {...everyone, fileCount: 9};
        state = docSiteReducer(state, SiteActions.uploadSiteSuccess({scope: 1, upload: {site: replaced, skipped: []}}));
        expect(state.sites).toEqual([replaced, sales]);

        state = docSiteReducer(state, SiteActions.deleteSiteSuccess({scope: 1, id: 2}));
        expect(state.sites).toEqual([replaced]);
    });

    it("forgets another tenant's websites and ignores late results for it", () => {
        let state = docSiteReducer(initialState, SiteActions.loadSites({scope: 1}));
        state = docSiteReducer(state, SiteActions.loadSitesSuccess({scope: 1, sites: [sales], maxZipBytes: 2048}));
        state = docSiteReducer(state, SiteActions.loadSites({scope: 2}));
        expect(state.sites).toEqual([]);
        expect(
            docSiteReducer(state, SiteActions.loadSitesSuccess({scope: 1, sites: [sales], maxZipBytes: 2048})).sites,
        ).toEqual([]);
        expect(
            docSiteReducer(state, SiteActions.uploadSiteSuccess({scope: 1, upload: {site: everyone, skipped: []}}))
                .sites,
        ).toEqual([]);
    });

    it("ignores websites answered after the logout, before the next login opened them", () => {
        expect(
            docSiteReducer(
                initialState,
                SiteActions.loadSitesSuccess({scope: "self", sites: [sales], maxZipBytes: 2048}),
            ),
        ).toBe(initialState);
    });

    it("shows a website once its address is there, and none while the next one is fetched", () => {
        let state = docSiteReducer(initialState, SiteActions.loadHandbookSuccess({sites: [sales, everyone]}));
        expect(state.handbook.map(site => site.id)).toEqual([1, 2]);
        expect(state.handbookLoaded).toBe(true);

        state = docSiteReducer(state, SiteActions.openSiteSuccess({scope: null, siteId: 1, url: "/doc-sites/t/"}));
        expect(state.opened).toEqual({siteId: 1, url: "/doc-sites/t/"});
        state = docSiteReducer(state, SiteActions.openSite({scope: null, id: 2}));
        expect(state.opened).toBeNull();
    });

    it("ends the loading when the handbook or its website's address fails, and tries again from the start", () => {
        let state = docSiteReducer(initialState, SiteActions.loadHandbook());
        state = docSiteReducer(state, SiteActions.loadHandbookFailure({error: "503"}));
        expect(state.handbookLoaded).toBe(true);
        expect(state.handbookFailed).toBe(true);
        state = docSiteReducer(state, SiteActions.loadHandbook());
        expect(state.handbookFailed).toBe(false);

        state = docSiteReducer(state, SiteActions.loadHandbookSuccess({sites: [everyone]}));
        state = docSiteReducer(state, SiteActions.openSite({scope: null, id: 1}));
        state = docSiteReducer(state, SiteActions.openSiteFailure({scope: null, error: "404"}));
        expect(state.opened).toBeNull();
        expect(state.openFailed).toBe(true);
        state = docSiteReducer(state, SiteActions.openSite({scope: null, id: 1}));
        expect(state.openFailed).toBe(false);
    });

    it("leaves the handbook alone when a preview's address fails", () => {
        const state = docSiteReducer(initialState, SiteActions.openSiteFailure({scope: 1, error: "404"}));
        expect(state.openFailed).toBe(false);
    });

    it("keeps a preview's address apart: closing the preview leaves the handbook's website shown", () => {
        let state = docSiteReducer(
            initialState,
            SiteActions.openSiteSuccess({scope: null, siteId: 1, url: "/doc-sites/a/"}),
        );
        state = docSiteReducer(state, SiteActions.openSite({scope: "self", id: 2}));
        expect(state.opened).toEqual({siteId: 1, url: "/doc-sites/a/"});

        state = docSiteReducer(state, SiteActions.openSiteSuccess({scope: "self", siteId: 2, url: "/doc-sites/b/"}));
        expect(state.preview).toEqual({siteId: 2, url: "/doc-sites/b/"});

        state = docSiteReducer(state, SiteActions.closeSite({scope: "self"}));
        expect(state.preview).toBeNull();
        expect(state.opened).toEqual({siteId: 1, url: "/doc-sites/a/"});
    });
});
