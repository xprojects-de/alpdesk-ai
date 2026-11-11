import {homePathFor} from "./auth.util";

describe("homePathFor", () => {
    it("sends the admin to the dashboard and a tenant user to the app", () => {
        expect(homePathFor(["ROLE_ADMIN"])).toBe("/dashboard");
        expect(homePathFor(["ROLE_TENANT_USER"])).toBe("/app");
    });

    it("has no area for a login without a known role", () => {
        expect(homePathFor([])).toBeNull();
        expect(homePathFor(["ROLE_TENANT"])).toBeNull();
    });
});
