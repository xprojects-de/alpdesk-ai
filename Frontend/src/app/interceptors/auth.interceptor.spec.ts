import {TestBed} from "@angular/core/testing";
import {HttpClient, provideHttpClient, withInterceptors} from "@angular/common/http";
import {HttpTestingController, provideHttpClientTesting} from "@angular/common/http/testing";
import {Store} from "@ngrx/store";
import {vi} from "vitest";
import {AuthService} from "../services/auth.service";
import * as AuthActions from "../store/auth/auth.actions";
import {authInterceptor} from "./auth.interceptor";

/**
 * Behind a WAM proxy another person signing in on the same computer makes the backend answer 403: the interceptor
 * logs out - forced, so the login page signs the new person in. The WAM sign-in itself never carries a token.
 */
describe("authInterceptor", () => {
    function setup() {
        const store = {dispatch: vi.fn()};
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withInterceptors([authInterceptor])),
                provideHttpClientTesting(),
                {provide: Store, useValue: store},
                {provide: AuthService, useValue: {getToken: () => "jwt", isTokenExpired: () => false}},
            ],
        });
        return {http: TestBed.inject(HttpClient), backend: TestBed.inject(HttpTestingController), store};
    }

    it("logs out on a 403 - a forced logout, not one by the user", () => {
        const {http, backend, store} = setup();
        http.get("/tenant/account").subscribe({error: () => undefined});

        const request = backend.expectOne("/tenant/account");
        expect(request.request.headers.get("Authorization")).toBe("Bearer jwt");
        request.flush({message: "Forbidden"}, {status: 403, statusText: "Forbidden"});

        expect(store.dispatch).toHaveBeenCalledWith(AuthActions.logout());
        expect(store.dispatch).not.toHaveBeenCalledWith(AuthActions.logout({byUser: true}));
    });

    it("sends the WAM sign-in without a token, and its 409 (waiting) logs nobody out", () => {
        const {http, backend, store} = setup();
        http.post("/login/wam", {}).subscribe({error: () => undefined});

        const request = backend.expectOne("/login/wam");
        expect(request.request.headers.has("Authorization")).toBe(false);
        request.flush({message: "Your access is waiting for approval"}, {status: 409, statusText: "Conflict"});

        expect(store.dispatch).not.toHaveBeenCalled();
    });

    it("sends the token to every other address, also one with 'login' in it", () => {
        const {http, backend} = setup();
        http.post("/admin/tenants/1/branding/login-key", null).subscribe();
        http.post("/login", {}).subscribe();

        expect(backend.expectOne("/admin/tenants/1/branding/login-key").request.headers.get("Authorization")).toBe(
            "Bearer jwt",
        );
        expect(backend.expectOne("/login").request.headers.has("Authorization")).toBe(false);
    });
});
