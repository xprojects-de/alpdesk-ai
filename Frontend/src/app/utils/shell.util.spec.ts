import {initials, shellNavItem, ShellNavGroup} from "./shell.util";

const GROUPS: ShellNavGroup[] = [
    {label: null, items: [{path: "/app/chat", label: "Chat", icon: "forum"}]},
    {label: "Verwaltung", items: [{path: "/app/documents", label: "Firmenwissen", icon: "menu_book"}]},
];

describe("shell util", () => {
    it.each([
        ["/app/chat", null, "Chat"],
        ["/app/documents?page=2", "Verwaltung", "Firmenwissen"],
        ["/app/documents/7/users", "Verwaltung", "Firmenwissen"],
    ])("finds the menu entry of %s", (url, group, label) => {
        expect(shellNavItem(GROUPS, url)).toEqual({group, label});
    });

    it("leaves out a group named like its entry", () => {
        const groups: ShellNavGroup[] = [
            {label: "Mandanten", items: [{path: "/dashboard/tenants", label: "Mandanten", icon: "apartment"}]},
        ];

        expect(shellNavItem(groups, "/dashboard/tenants/4/users")).toEqual({group: null, label: "Mandanten"});
    });

    it("does not take an entry whose path only starts the same", () => {
        expect(shellNavItem(GROUPS, "/app/chatter")).toBeNull();
        expect(shellNavItem(GROUPS, "/app/unknown")).toBeNull();
    });

    it.each([
        ["Anna Berger", "AB"],
        ["anna maria berger", "AM"],
        ["a.berger", "AB"],
        ["admin", "A"],
        ["  ", ""],
    ])("shortens %s to %s", (name, expected) => {
        expect(initials(name)).toBe(expected);
    });
});
