import {FormControl, FormGroup} from "@angular/forms";
import {notBlank, passwordsMatch, username} from "./validators.util";

describe("validators.util", () => {
    it("notBlank rejects whitespace only, but not empty or real text", () => {
        expect(notBlank()(new FormControl("  "))).toEqual({blank: true});
        expect(notBlank()(new FormControl(""))).toBeNull();
        expect(notBlank()(new FormControl(" Haus "))).toBeNull();
    });

    it("username allows 3-100 letters, digits and . _ - @", () => {
        expect(username()(new FormControl("anna.mueller@musterfirma_1"))).toBeNull();
        expect(username()(new FormControl("  anna  "))).toBeNull();
        expect(username()(new FormControl(""))).toBeNull();
        expect(username()(new FormControl("ab"))).toEqual({username: true});
        expect(username()(new FormControl("a".repeat(101)))).toEqual({username: true});
        expect(username()(new FormControl("anna müller"))).toEqual({username: true});
        expect(username()(new FormControl("jürgen"))).toEqual({username: true});
    });

    it("passwordsMatch compares the repeat with the password, an empty repeat is not checked", () => {
        const group = (password: string, repeat: string) =>
            new FormGroup({password: new FormControl(password), repeat: new FormControl(repeat)});
        const validator = passwordsMatch("password", "repeat");
        expect(validator(group("geheim-geheim", "geheim-geheim"))).toBeNull();
        expect(validator(group("geheim-geheim", ""))).toBeNull();
        expect(validator(group("geheim-geheim", "geheim-geheiM"))).toEqual({passwordMismatch: true});
    });
});
