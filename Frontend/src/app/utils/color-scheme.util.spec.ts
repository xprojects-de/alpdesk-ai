import {
    applyColorScheme,
    COLOR_SCHEME_KEY,
    cssColorScheme,
    readColorScheme,
    storeColorScheme,
} from "./color-scheme.util";

describe("color scheme util", () => {
    it.each([
        ["light", "light"],
        ["dark", "dark"],
        ["system", "system"],
        [null, "system"],
        ["purple", "system"],
    ])("reads the stored %s as %s", (stored, expected) => {
        expect(readColorScheme({getItem: () => stored})).toBe(expected);
    });

    it("follows the system when the storage is missing or refuses", () => {
        expect(readColorScheme(null)).toBe("system");
        expect(
            readColorScheme({
                getItem: () => {
                    throw new Error("disabled");
                },
            }),
        ).toBe("system");
    });

    it("stores the choice and survives a storage that refuses", () => {
        const stored: Record<string, string> = {};
        storeColorScheme({setItem: (key, value) => (stored[key] = value)}, "dark");

        expect(stored[COLOR_SCHEME_KEY]).toBe("dark");
        expect(() =>
            storeColorScheme(
                {
                    setItem: () => {
                        throw new Error("disabled");
                    },
                },
                "light",
            ),
        ).not.toThrow();
    });

    it("lets the browser choose for the system, else forces one scheme", () => {
        expect(cssColorScheme("system")).toBe("light dark");
        expect(cssColorScheme("light")).toBe("light");
        expect(cssColorScheme("dark")).toBe("dark");
    });

    it("switches the page through the color-scheme property", () => {
        const root = document.createElement("html");
        applyColorScheme(root, "dark");

        expect(root.style.colorScheme).toBe("dark");
    });
});
