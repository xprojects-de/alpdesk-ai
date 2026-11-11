/** How much of the reasoning the collapsed line shows while the model thinks. */
export const THINKING_PREVIEW_LENGTH = 120;

/** The latest line of the model's reasoning, shortened from the front - what it is thinking about right now. */
export function thinkingPreview(thinking: string): string {
    const lines = thinking
        .split("\n")
        .map(line => line.trim())
        .filter(line => line !== "");
    const last = lines.at(-1) ?? "";
    return last.length <= THINKING_PREVIEW_LENGTH ? last : "…" + last.slice(last.length - THINKING_PREVIEW_LENGTH + 1);
}
