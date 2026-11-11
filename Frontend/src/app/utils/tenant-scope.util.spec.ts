import {tenantSettingsUrl} from "./tenant-scope.util";

describe("tenantSettingsUrl", () => {
    it("leads a tenant admin to its own tenant's settings", () => {
        expect(tenantSettingsUrl("http://host", "self")).toBe("http://host/tenant/admin");
    });

    it("leads the admin to the settings of the tenant with this id", () => {
        expect(tenantSettingsUrl("", 7)).toBe("/admin/tenants/7");
    });
});
