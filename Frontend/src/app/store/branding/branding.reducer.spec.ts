import {Branding, NEUTRAL_BRANDING, TenantBranding} from "../../models/branding.model";
import * as AuthActions from "../auth/auth.actions";
import * as BrandingActions from "./branding.actions";
import {brandingReducer, initialState} from "./branding.reducer";
import {selectBranding, selectLogoUrl} from "./branding.selectors";

describe("brandingReducer", () => {
    const installation: Branding = {name: "Werkstattwissen", primaryColor: "#0055aa", hasLogo: true};
    const tenant: Branding = {name: "Musterbau", primaryColor: "#aa0000", hasLogo: false};
    const own = (name: string): TenantBranding => ({
        name,
        primaryColor: null,
        hasLogo: false,
        defaultName: "Zirmo",
        defaultPrimaryColor: "#49732a",
        loginKey: "abc123",
    });

    it("shows the tenant's look in the tenant area and the installation's again after the logout", () => {
        let state = brandingReducer(
            initialState,
            BrandingActions.loadInstallationBrandingSuccess({branding: installation, logoUrl: "/branding/logo"}),
        );
        state = brandingReducer(state, BrandingActions.loadTenantBrandingSuccess({branding: tenant, logoUrl: null}));

        expect(selectBranding.projector(state, true)).toEqual(tenant);
        expect(selectLogoUrl.projector(state, true)).toBeNull();

        state = brandingReducer(state, AuthActions.logoutSuccess());

        expect(selectBranding.projector(state, true)).toEqual(installation);
        expect(selectLogoUrl.projector(state, true)).toBe("/branding/logo");
    });

    it("shows no brand before the login - one login page for every tenant", () => {
        const state = brandingReducer(
            initialState,
            BrandingActions.loadInstallationBrandingSuccess({branding: installation, logoUrl: "/branding/logo"}),
        );

        expect(selectBranding.projector(state, false)).toEqual(NEUTRAL_BRANDING);
        expect(selectLogoUrl.projector(state, false)).toBeNull();
    });

    it("shows a tenant's look on its own login page - and none again on /login", () => {
        let state = brandingReducer(
            initialState,
            BrandingActions.loadLoginBrandingSuccess({
                loginKey: "abc123",
                branding: tenant,
                logoUrl: "/branding/login/abc123/logo",
            }),
        );

        expect(selectBranding.projector(state, false)).toEqual(tenant);
        expect(selectLogoUrl.projector(state, false)).toBe("/branding/login/abc123/logo");

        state = brandingReducer(state, BrandingActions.clearLoginBranding());

        expect(selectBranding.projector(state, false)).toEqual(NEUTRAL_BRANDING);
        expect(selectLogoUrl.projector(state, false)).toBeNull();
    });

    it("keeps the tenant's login page across the logout - the logout leads back to it", () => {
        let state = brandingReducer(
            initialState,
            BrandingActions.loadLoginBrandingSuccess({loginKey: "abc123", branding: tenant, logoUrl: null}),
        );
        state = brandingReducer(state, AuthActions.logoutSuccess());

        expect(selectBranding.projector(state, false)).toEqual(tenant);
    });

    it("an unknown key leaves the login page neutral", () => {
        const state = brandingReducer(
            brandingReducer(
                initialState,
                BrandingActions.loadLoginBrandingSuccess({loginKey: "abc123", branding: tenant, logoUrl: null}),
            ),
            BrandingActions.loadLoginBrandingFailure({error: "x", unknownKey: true}),
        );

        expect(selectBranding.projector(state, false)).toEqual(NEUTRAL_BRANDING);
    });

    it("falls back to the build's own look while nothing is loaded", () => {
        expect(selectBranding.projector(initialState, true).name).toBe("Zirmo");
    });

    it("ignores the admin's results for a tenant whose dialog is no longer open", () => {
        const state = brandingReducer(initialState, BrandingActions.loadAdminBranding({tenantId: 2}));

        expect(
            brandingReducer(
                state,
                BrandingActions.loadAdminBrandingSuccess({tenantId: 1, branding: own("A"), logoUrl: null}),
            ),
        ).toBe(state);
        expect(
            brandingReducer(state, BrandingActions.saveAdminBrandingSuccess({tenantId: 1, branding: own("A")})),
        ).toBe(state);
    });
});
