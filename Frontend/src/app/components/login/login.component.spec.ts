import {TestBed} from "@angular/core/testing";
import {Router} from "@angular/router";
import {MockStore, provideMockStore} from "@ngrx/store/testing";
import {vi} from "vitest";
import {AuthService} from "../../services/auth.service";
import * as AuthActions from "../../store/auth/auth.actions";
import {initialState} from "../../store/auth/auth.reducer";
import {AuthState} from "../../models/auth.model";
import {LoginComponent} from "./login.component";
import {translocoTesting} from "../../i18n/i18n.testing";
import * as BrandingActions from "../../store/branding/branding.actions";
import {initialState as brandingInitialState} from "../../store/branding/branding.reducer";

/** The login page decides between the password form and the WAM proxy - and must never sign in again on its own after a logout. */
describe("LoginComponent", () => {
    function render(auth: Partial<AuthState>, signedOut = false, loginKey?: string) {
        TestBed.configureTestingModule({
            imports: [LoginComponent, translocoTesting("de")],
            providers: [
                provideMockStore({initialState: {auth: {...initialState, ...auth}, branding: brandingInitialState}}),
                {provide: Router, useValue: {navigate: vi.fn(() => Promise.resolve(true))}},
                {provide: AuthService, useValue: {isSignedOut: () => signedOut}},
            ],
        });
        const store = TestBed.inject(MockStore);
        const dispatched = vi.spyOn(store, "dispatch");
        const fixture = TestBed.createComponent(LoginComponent);
        if (loginKey) {
            fixture.componentRef.setInput("loginKey", loginKey);
        }
        fixture.detectChanges();
        const element = fixture.nativeElement as HTMLElement;
        return {fixture, store, element, dispatched};
    }

    function wamSignIns(dispatched: {mock: {calls: unknown[][]}}): number {
        return dispatched.mock.calls.filter(
            ([action]) => (action as {type: string}).type === AuthActions.wamSignIn.type,
        ).length;
    }

    function button(element: HTMLElement, text: string): HTMLButtonElement | undefined {
        return [...element.querySelectorAll("button")].find(b => b.textContent?.includes(text));
    }

    it("without WAM shows the password form and never signs in through the proxy", () => {
        const {element, dispatched} = render({wamEnabled: false});

        expect(dispatched).toHaveBeenCalledWith(AuthActions.loadWamStatus());
        expect(element.querySelector("input[type=password]")).not.toBeNull();
        expect(wamSignIns(dispatched)).toBe(0);
    });

    it("with WAM signs in through the proxy at once - exactly once, and hides the password form", () => {
        const {fixture, store, element, dispatched} = render({wamEnabled: true});
        store.setState({auth: {...initialState, wamEnabled: true, loading: true}, branding: brandingInitialState});
        fixture.detectChanges();

        expect(wamSignIns(dispatched)).toBe(1);
        expect(element.querySelector("input[type=password]")).toBeNull();
        expect(element.textContent).toContain("Als Administrator anmelden");
    });

    it("after the user logged out it waits for 'Erneut anmelden' instead of signing in again", () => {
        const {fixture, element, dispatched} = render({wamEnabled: true}, true);

        expect(wamSignIns(dispatched)).toBe(0);
        expect(element.textContent).toContain("Sie sind abgemeldet.");

        button(element, "Erneut anmelden")!.click();
        fixture.detectChanges();

        expect(wamSignIns(dispatched)).toBe(1);
    });

    it("tells someone waiting for approval so, and shows the admin's password form only on request", () => {
        const {fixture, element} = render({wamEnabled: true, wamWaiting: true});

        expect(element.textContent).toContain("Zugang angefragt");
        expect(element.querySelector("input[type=password]")).toBeNull();

        button(element, "Als Administrator anmelden")!.click();
        fixture.detectChanges();

        expect(element.querySelector("input[type=password]")).not.toBeNull();
    });

    it("on /login shows no brand and clears the look of a tenant's login page seen before", () => {
        const {element, dispatched} = render({wamEnabled: false});

        expect(dispatched).toHaveBeenCalledWith(BrandingActions.clearLoginBranding());
        expect(element.querySelector(".login.branded")).toBeNull();
    });

    it("on a tenant's login page loads its look and shows its name", () => {
        const {fixture, store, element, dispatched} = render({wamEnabled: false}, false, "abc123");

        expect(dispatched).toHaveBeenCalledWith(BrandingActions.loadLoginBranding({loginKey: "abc123"}));

        store.setState({
            auth: {...initialState, wamEnabled: false},
            branding: {
                ...brandingInitialState,
                login: {name: "Musterbau GmbH", primaryColor: "#1a4f8b", hasLogo: true},
                loginLogoUrl: "/branding/login/abc123/logo",
            },
        });
        fixture.detectChanges();

        expect(element.querySelector(".login.branded")).not.toBeNull();
        expect(element.querySelector(".brand-name")?.textContent).toContain("Musterbau GmbH");
        expect(element.querySelector<HTMLImageElement>(".logo-tile img")?.getAttribute("src")).toBe(
            "/branding/login/abc123/logo",
        );
    });

    it("lets a person with users in several tenants choose one - the click signs in there", () => {
        const tenants = [
            {id: 4, name: "Allgemeiner Chat", loginKey: "k4"},
            {id: 7, name: "Thema Technik", loginKey: "k7"},
        ];
        const {fixture, element, dispatched} = render({wamEnabled: true, wamChoice: tenants}, true);

        expect(element.textContent).toContain("Sie haben Zugang zu mehreren Firmen");
        button(element, "Thema Technik")!.click();
        fixture.detectChanges();

        expect(dispatched).toHaveBeenCalledWith(AuthActions.wamSignIn({tenantId: 7}));
    });

    it("on a tenant's login page sends its key with the password login - it picks the tenant of a name in several", () => {
        const {element, dispatched, fixture} = render({wamEnabled: false}, false, "abc123");
        const [username, password] = element.querySelectorAll<HTMLInputElement>("input");
        username.value = "anna";
        username.dispatchEvent(new Event("input"));
        password.value = "geheim-genug-123";
        password.dispatchEvent(new Event("input"));
        fixture.detectChanges();

        element.querySelector<HTMLFormElement>("form")!.dispatchEvent(new Event("submit"));

        expect(dispatched).toHaveBeenCalledWith(
            AuthActions.login({credentials: {username: "anna", password: "geheim-genug-123", loginKey: "abc123"}}),
        );
    });
});
