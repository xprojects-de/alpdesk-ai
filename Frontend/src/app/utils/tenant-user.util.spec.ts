import {TenantUser} from "../models/tenant-user.model";
import {
    buildTenantUserRequest,
    filterTenantUsers,
    languageLabel,
    tenantUserStatus,
    TenantUserFormValue,
    usersCreatedByAdminOnly,
    withUnlistedGroups,
} from "./tenant-user.util";

describe("usersCreatedByAdminOnly", () => {
    it("holds for the tenant admin with WAM - never without WAM, never for the admin", () => {
        expect(usersCreatedByAdminOnly("self", true)).toBe(true);
        expect(usersCreatedByAdminOnly("self", false)).toBe(false);
        expect(usersCreatedByAdminOnly(7, true)).toBe(false);
        expect(usersCreatedByAdminOnly(7, false)).toBe(false);
    });
});

describe("withUnlistedGroups", () => {
    it("keeps the user's groups the dialog could not offer, and takes away only listed ones", () => {
        expect(withUnlistedGroups([1], [1, 2, 9], [1, 2])).toEqual([1, 9]);
        expect(withUnlistedGroups([], [9], [1, 2])).toEqual([9]);
        expect(withUnlistedGroups([1, 9], [9], [1])).toEqual([1, 9]);
    });
});

describe("buildTenantUserRequest", () => {
    const form = (password: string, language = "de"): TenantUserFormValue => ({
        username: " anna.mueller ",
        displayName: " Anna Müller ",
        password,
        active: false,
        admin: false,
        groupIds: [3],
        language,
    });

    it("sends the trimmed names, the active flag, the groups, the language and the password", () => {
        expect(buildTenantUserRequest(form("ein-langes-passwort"))).toEqual({
            username: "anna.mueller",
            displayName: "Anna Müller",
            password: "ein-langes-passwort",
            active: false,
            admin: false,
            groupIds: [3],
            language: "de",
        });
    });

    it("always sends the language chosen", () => {
        expect(buildTenantUserRequest(form("", "en")).language).toBe("en");
    });

    it("leaves an empty password out, so it stays unchanged", () => {
        expect("password" in buildTenantUserRequest(form(""))).toBe(false);
    });

    it("on edit sends the groups only when they were changed in the dialog - in whatever order", () => {
        expect("groupIds" in buildTenantUserRequest(form(""), [3])).toBe(false);
        expect(buildTenantUserRequest({...form(""), groupIds: [4, 3]}, [3, 4]).groupIds).toBeUndefined();
        expect(buildTenantUserRequest({...form(""), groupIds: []}, [3]).groupIds).toEqual([]);
        expect(buildTenantUserRequest(form(""), []).groupIds).toEqual([3]);
    });
});

describe("tenantUserStatus", () => {
    const user = (active: boolean, lastLoginAt: string | null) => ({active, lastLoginAt}) as TenantUser;

    it("tells a user waiting for approval from a deactivated one - only with the WAM proxy", () => {
        expect(tenantUserStatus(user(true, null), true)).toEqual({label: "users.status.active", tone: "ok"});
        expect(tenantUserStatus(user(false, null), true)).toEqual({label: "users.status.waiting", tone: "warn"});
        expect(tenantUserStatus(user(false, "2026-10-07T08:00:00Z"), true)).toEqual({
            label: "users.status.inactive",
            tone: "",
        });
        expect(tenantUserStatus(user(false, null), false)).toEqual({label: "users.status.inactive", tone: ""});
    });
});

describe("languageLabel", () => {
    it("names the language the frontend speaks for the user, by its own name", () => {
        expect(languageLabel("en")).toBe("English");
        expect(languageLabel("de")).toBe("Deutsch");
        expect(languageLabel("de-AT")).toBe("Deutsch");
        expect(languageLabel("fr")).toBe("English");
        expect(languageLabel(null)).toBe("English");
    });
});

describe("filterTenantUsers", () => {
    const users = [
        {username: "anna.mueller", displayName: "Anna Müller"},
        {username: "bernd", displayName: "Bernd Huber"},
    ] as TenantUser[];

    it("finds a user by username or name, whatever the case", () => {
        expect(filterTenantUsers(users, "MÜLLER").map(user => user.username)).toEqual(["anna.mueller"]);
        expect(filterTenantUsers(users, " bernd ").map(user => user.username)).toEqual(["bernd"]);
        expect(filterTenantUsers(users, "huber").map(user => user.username)).toEqual(["bernd"]);
    });

    it("keeps everyone without a search text and no one without a match", () => {
        expect(filterTenantUsers(users, "")).toEqual(users);
        expect(filterTenantUsers(users, "carla")).toEqual([]);
    });
});
