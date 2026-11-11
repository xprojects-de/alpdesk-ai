import {TestBed} from "@angular/core/testing";
import {provideHttpClient} from "@angular/common/http";
import {HttpTestingController, provideHttpClientTesting} from "@angular/common/http/testing";
import {vi} from "vitest";
import {AuthService} from "./auth.service";

describe("AuthService", () => {
    it("tells of another tab's session only when the token changed or the storage was cleared", () => {
        TestBed.configureTestingModule({providers: [provideHttpClient()]});
        const service = TestBed.inject(AuthService);
        let changes = 0;
        const subscription = service.sessionChangedInOtherTab().subscribe(() => changes++);

        window.dispatchEvent(new StorageEvent("storage", {key: "color_scheme"}));
        window.dispatchEvent(new StorageEvent("storage", {key: "auth_token"}));
        window.dispatchEvent(new StorageEvent("storage", {key: null}));
        subscription.unsubscribe();

        expect(changes).toBe(2);
    });

    it("signs in through the WAM proxy with the browser's language - a user it creates speaks it", () => {
        TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting()]});
        vi.spyOn(navigator, "languages", "get").mockReturnValue(["de-DE", "en"]);
        const service = TestBed.inject(AuthService);
        const http = TestBed.inject(HttpTestingController);

        service.wamLogin().subscribe();
        service.wamLogin(7).subscribe();

        const [first, chosen] = http.match(request => request.url.endsWith("/login/wam"));
        expect(first.request.body).toEqual({language: "de"});
        expect(chosen.request.body).toEqual({tenantId: 7, language: "de"});
        vi.restoreAllMocks();
    });
});
