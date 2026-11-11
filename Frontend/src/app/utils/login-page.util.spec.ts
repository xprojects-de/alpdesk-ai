import {LOGIN_KEY_KEY, loginAddress, loginPath, readLoginKey, storeLoginKey} from "./login-page.util";

describe("login page util", () => {
    function memoryStorage(): Pick<Storage, "getItem" | "setItem" | "removeItem"> {
        const values = new Map<string, string>();
        return {
            getItem: key => values.get(key) ?? null,
            setItem: (key, value) => void values.set(key, value),
            removeItem: key => void values.delete(key),
        };
    }

    it("remembers the key of the tenant's login page and forgets it again", () => {
        const storage = memoryStorage();

        storeLoginKey(storage, "abc123");
        expect(readLoginKey(storage)).toBe("abc123");

        storeLoginKey(storage, null);
        expect(readLoginKey(storage)).toBeNull();
    });

    it("works without storage - also where it throws", () => {
        const throwing = {
            getItem: () => {
                throw new Error("blocked");
            },
            setItem: () => {
                throw new Error("blocked");
            },
            removeItem: () => {
                throw new Error("blocked");
            },
        } as unknown as Storage;

        expect(readLoginKey(null)).toBeNull();
        expect(readLoginKey(throwing)).toBeNull();
        expect(() => storeLoginKey(throwing, "abc")).not.toThrow();
    });

    it("treats an empty stored value as none", () => {
        const storage = memoryStorage();
        storage.setItem(LOGIN_KEY_KEY, "");

        expect(readLoginKey(storage)).toBeNull();
    });

    it("leads a logout to the remembered login page, else to /login", () => {
        expect(loginPath("abc123")).toEqual(["/login", "abc123"]);
        expect(loginPath(null)).toEqual(["/login"]);
    });

    it("builds the address below the base href", () => {
        expect(loginAddress("https://ai.example.com/", "abc123")).toBe("https://ai.example.com/login/abc123");
        expect(loginAddress("https://example.com/alpdesk-ai/", "abc123")).toBe(
            "https://example.com/alpdesk-ai/login/abc123",
        );
    });
});
