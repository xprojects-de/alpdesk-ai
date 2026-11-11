import {TestBed} from "@angular/core/testing";
import {TranslocoService} from "@jsverse/transloco";
import {translocoTesting} from "./i18n.testing";
import {SinglePassTranspiler} from "./single-pass-transpiler";

describe("SinglePassTranspiler", () => {
    function transpile(value: unknown, params: Record<string, unknown> = {}): unknown {
        TestBed.configureTestingModule({providers: [SinglePassTranspiler]});
        return TestBed.inject(SinglePassTranspiler).transpile({value, params, translation: {}, key: "k"});
    }

    it("writes the params in", () => {
        expect(transpile("{{start}} – {{ end }} of {{length}}", {start: 1, end: 50, length: 0})).toBe("1 – 50 of 0");
    });

    it("keeps braces a value brings along - a typed name is text, not a placeholder", () => {
        expect(transpile('Delete "{{path}}"?', {path: "Brief_{{Datum}}.docx"})).toBe('Delete "Brief_{{Datum}}.docx"?');
    });

    it("ends with a value holding its own placeholder", () => {
        expect(transpile("{{name}}", {name: "Team {{name}}"})).toBe("Team {{name}}");
    });

    it("leaves out a placeholder without a param", () => {
        expect(transpile("Hello {{name}}", {})).toBe("Hello ");
    });

    it("is the transpiler of the app's translations", () => {
        TestBed.configureTestingModule({imports: [translocoTesting("en")]});
        expect(TestBed.inject(TranslocoService).translate("knowledgeAreas.name", {name: "Projekt {{name}}"})).toBe(
            "Projekt {{name}}",
        );
    });
});
