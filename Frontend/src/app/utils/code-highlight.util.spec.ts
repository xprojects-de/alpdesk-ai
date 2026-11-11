import {highlightCode} from "./code-highlight.util";

describe("highlightCode", () => {
    it.each([
        ["java", "Java"],
        ["groovy", "Groovy"],
        ["ts", "TypeScript"],
        ["js", "JavaScript"],
        ["py", "Python"],
        ["sh", "Bash"],
        ["html", "HTML, XML"],
        ["yml", "YAML"],
        ["SQL", "SQL"],
    ])("highlights %s as %s", (lang, name) => {
        const code = highlightCode("x = 1", lang);
        expect(code?.name).toBe(name);
    });

    it("marks keywords and strings in spans", () => {
        const html = highlightCode('public class Main { String s = "a"; }', "java")!.html;
        expect(html).toContain('<span class="hljs-keyword">public</span>');
        expect(html).toContain('<span class="hljs-string">&quot;a&quot;</span>');
    });

    it("escapes the code", () => {
        expect(highlightCode("<script>alert(1)</script>", "plaintext")!.html).toBe(
            "&lt;script&gt;alert(1)&lt;/script&gt;",
        );
    });

    it.each(["", "brainfuck", "unbekannt"])("leaves a language not known here (%s) unhighlighted", lang => {
        expect(highlightCode("x = 1", lang)).toBeNull();
    });
});
