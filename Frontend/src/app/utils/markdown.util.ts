import {Marked, Renderer, Tokens} from "marked";
import {highlightCode} from "./code-highlight.util";

const markdown = new Marked({
    gfm: true,
    // A single line break in an answer is meant as one, like in a chat.
    breaks: true,
    renderer: {
        link(token: Tokens.Link): string {
            // Only web and mail links are links. Models like to make up links to files they created
            // ("sandbox:/name.docx") - the download is shown below the answer, so such a link stays its text.
            if (!/^(https?:|mailto:)/i.test(token.href)) {
                return this.parser.parseInline(token.tokens);
            }
            // Links of an answer leave the app in a new tab; Angular's sanitizer keeps target and rel.
            const html = Renderer.prototype.link.call(this, token);
            return html.replace(/^<a /, '<a target="_blank" rel="noopener noreferrer" ');
        },
        code(token: Tokens.Code): string {
            return codeBlock(token.text, token.lang);
        },
    },
});

/** The class of a code block's copy control, which the chat listens for. */
export const COPY_CODE_CLASS = "code-copy";

/** The texts of a code block's copy control, in the user's language. */
export interface CopyCodeLabels {
    /** The control's text. */
    copy: string;
    /** Its tooltip. */
    title: string;
}

/**
 * A code block with its language and a copy control above it. The sanitizer of [innerHTML] removes buttons, so the
 * control is a span with role and tabindex; the chat handles its clicks (copiedCode).
 */
function codeBlock(text: string, info: string | undefined): string {
    // The info string may carry more than the language ("java title=Main.java").
    const lang = info?.trim().split(/\s+/)[0] ?? "";
    const highlighted = highlightCode(text, lang);
    const codeClass = highlighted ? ` class="hljs language-${highlighted.language}"` : "";
    const label = highlighted?.name ?? (lang || "Code");
    return (
        `<div class="code-block"><div class="code-head"><span class="code-lang">${escapeHtml(label)}</span>` +
        `<span class="${COPY_CODE_CLASS}" role="button" tabindex="0"></span></div>` +
        `<pre><code${codeClass}>${highlighted?.html ?? escapeHtml(text)}</code></pre></div>\n`
    );
}

/** The code of the block whose copy control was clicked - null for a click anywhere else in an answer. */
export function copiedCode(target: EventTarget | null): string | null {
    if (!(target instanceof Element) || !target.classList.contains(COPY_CODE_CLASS)) {
        return null;
    }
    return target.closest(".code-block")?.querySelector("code")?.textContent ?? null;
}

/** Elements the browser loads on its own as soon as they are shown. */
const LOADING_ELEMENTS = "img, picture, video, audio, source, track";
/** Attributes the sanitizer keeps that load an address on their own (a table's background image, a poster). */
const LOADING_ATTRIBUTES = ["background", "poster", "srcset"];

/**
 * HTML of an assistant answer written in Markdown (GFM: tables, lists). Raw HTML in it passes through on
 * purpose: the result is bound with [innerHTML], whose sanitizer removes scripts and event handlers.
 */
export function renderMarkdown(text: string, copyLabels: CopyCodeLabels): string {
    try {
        // A model caught in a loop of blanks (padding a table) is stopped by the backend - an answer stored before
        // that still has them, and marked needs seconds for them in the browser, or overflows.
        const html = markdown.parse(text.replace(/[ \t]{200,}/g, " "), {async: false});
        return finished(html, copyLabels);
    } catch {
        // A pathological answer (e.g. a model caught in a loop of blanks in a table) can overflow marked's recursion:
        // shown as plain text then, rather than the chat hanging.
        return `<p class="plain">${escapeHtml(text)}</p>`;
    }
}

/** The answer's HTML made safe, its copy controls labelled - set through the DOM, so the labels need no escaping. */
function finished(html: string, copyLabels: CopyCodeLabels): string {
    // A parsed document is inert: nothing in it is loaded.
    const body = new DOMParser().parseFromString(html, "text/html").body;
    removeUnsafeContent(body);
    body.querySelectorAll(`.${COPY_CODE_CLASS}`).forEach(control => {
        control.textContent = copyLabels.copy;
        control.setAttribute("title", copyLabels.title);
    });
    return body.innerHTML;
}

/**
 * Leaves out what the browser would load by itself: an image in an answer is fetched the moment it is shown, without a
 * click - a document or a tool result with hidden instructions could make the model write an image address carrying
 * the company's data to a third party. An image keeps its alternative text. The sanitizer keeps all of these.
 * A raw HTML link other than a web or mail link stays its text, like a Markdown one: the sanitizer keeps every scheme
 * but "javascript:", so hidden instructions could make the model offer a click into a program of the computer.
 */
function removeUnsafeContent(body: HTMLElement): void {
    body.querySelectorAll(LOADING_ELEMENTS).forEach(element => element.replaceWith(element.getAttribute("alt") ?? ""));
    for (const attribute of LOADING_ATTRIBUTES) {
        body.querySelectorAll(`[${attribute}]`).forEach(element => element.removeAttribute(attribute));
    }
    body.querySelectorAll("a[href]").forEach(link => {
        if (!/^\s*(https?:|mailto:)/i.test(link.getAttribute("href") ?? "")) {
            link.replaceWith(...Array.from(link.childNodes));
        }
    });
}

function escapeHtml(text: string): string {
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
