import {LAST_TENANT_KEY, readLastTenant, storeLastTenant} from "./last-tenant.util";

function memory(): Pick<Storage, "getItem" | "setItem" | "removeItem"> {
    const values = new Map<string, string>();
    return {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => void values.set(key, value),
        removeItem: key => void values.delete(key),
    };
}

describe("last-tenant.util", () => {
    it("keeps the chosen tenant and forgets it with null", () => {
        const storage = memory();
        storeLastTenant(storage, 42);
        expect(readLastTenant(storage)).toBe(42);
        storeLastTenant(storage, null);
        expect(readLastTenant(storage)).toBeUndefined();
    });

    it("reads nothing of a value that is no tenant id", () => {
        const storage = memory();
        for (const value of ["", "abc", "-3", "1.5", "0"]) {
            storage.setItem(LAST_TENANT_KEY, value);
            expect(readLastTenant(storage)).toBeUndefined();
        }
    });

    it("survives a storage that throws or is missing", () => {
        const broken = {
            getItem: () => {
                throw new Error("blocked");
            },
            setItem: () => {
                throw new Error("blocked");
            },
            removeItem: () => {
                throw new Error("blocked");
            },
        };
        expect(readLastTenant(broken)).toBeUndefined();
        expect(() => storeLastTenant(broken, 1)).not.toThrow();
        expect(readLastTenant(null)).toBeUndefined();
    });
});
