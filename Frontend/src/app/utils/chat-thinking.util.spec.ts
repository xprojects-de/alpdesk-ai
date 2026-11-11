import {THINKING_PREVIEW_LENGTH, thinkingPreview} from "./chat-thinking.util";

describe("thinkingPreview", () => {
    it("shows the latest line that has text", () => {
        expect(thinkingPreview("Erst die Doku lesen.\nDann die Zeiten vergleichen.\n\n")).toBe(
            "Dann die Zeiten vergleichen.",
        );
        expect(thinkingPreview("")).toBe("");
    });

    it("shortens a long line from the front, keeping its end", () => {
        const long = "a".repeat(200) + "Ende";
        const preview = thinkingPreview(long);
        expect(preview.length).toBe(THINKING_PREVIEW_LENGTH);
        expect(preview.startsWith("…")).toBe(true);
        expect(preview.endsWith("Ende")).toBe(true);
    });
});
