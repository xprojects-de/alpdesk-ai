import {buildTenantBrandingRequest, isHexColor, themeVariables} from "./branding.util";

describe("branding util", () => {
    describe("themeVariables", () => {
        it("computes Material 3's colour roles from one colour, for light and dark", () => {
            const variables = themeVariables("#1a4f8b");
            const roles = Object.keys(variables).filter(name => name.startsWith("--mat-sys-"));

            expect(roles.length).toBe(33);
            for (const role of roles) {
                expect(variables[role]).toMatch(/^light-dark\(#[0-9a-f]{6}, #[0-9a-f]{6}\)$/);
            }
            expect(variables["--mat-sys-on-primary"]).toMatch(/^light-dark\(#ffffff, /);
        });

        it("leaves the surfaces and the text on them to the theme - neutral whatever the colour", () => {
            const variables = themeVariables("#1a4f8b");

            expect(variables["--mat-sys-surface"]).toBeUndefined();
            expect(variables["--mat-sys-surface-container"]).toBeUndefined();
            expect(variables["--mat-sys-on-surface"]).toBeUndefined();
        });

        it("keeps the brand colour as it is - the primary container is the colour itself", () => {
            const variables = themeVariables("#1A4F8B");
            const lightPrimary = variables["--mat-sys-primary"].slice("light-dark(".length);
            const blue = parseInt(lightPrimary.slice(5, 7), 16);
            const red = parseInt(lightPrimary.slice(1, 3), 16);

            expect(variables["--mat-sys-primary-container"]).toMatch(/^light-dark\(#1a4f8b, /);
            expect(variables["--app-brand"]).toBe("#1a4f8b");
            expect(variables["--app-on-brand"]).toMatch(/^#[0-9a-f]{6}$/);
            expect(blue).toBeGreaterThan(red);
        });

        it("sets nothing for the build's own colour - its theme stays exactly as built", () => {
            expect(themeVariables("#49732A")).toEqual({});
        });

        it("leaves the error colours to the theme", () => {
            expect(Object.keys(themeVariables("#000000")).some(name => name.includes("error"))).toBe(false);
        });
    });

    it("recognises a colour as #rrggbb", () => {
        expect(isHexColor("#1A4f8b")).toBe(true);
        expect(isHexColor("#1a4f8")).toBe(false);
        expect(isHexColor("blue")).toBe(false);
    });

    it("sends trimmed values in lower case, an empty one as the default (null)", () => {
        expect(buildTenantBrandingRequest({name: "  Musterbau  ", primaryColor: " #1A4F8B "})).toEqual({
            name: "Musterbau",
            primaryColor: "#1a4f8b",
        });
        expect(buildTenantBrandingRequest({name: " ", primaryColor: ""})).toEqual({name: null, primaryColor: null});
    });
});
