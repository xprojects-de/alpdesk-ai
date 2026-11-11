/** One server-sent event: its name ("message" if the block has none) and its data lines joined by "\n". */
export interface SseEvent {
    event: string;
    data: string;
}

/**
 * Reads a text/event-stream from the growing response text (XHR's partialText is the whole text so far):
 * each call takes what is new since the last one and returns the events completed by it. A block split
 * across calls - even inside a line or between "\r" and "\n" - waits for its end.
 */
export class SseReader {
    private offset = 0;
    private rest = "";

    /** `textSoFar`: the whole response text received until now. */
    read(textSoFar: string): SseEvent[] {
        const buffer = (this.rest + textSoFar.slice(this.offset)).replace(/\r\n/g, "\n");
        this.offset = textSoFar.length;
        const blocks = buffer.split("\n\n");
        // A lone "\r" at the end may be the first half of a "\r\n" - kept for the next call.
        this.rest = blocks.pop() ?? "";
        return blocks.map(parseBlock).filter((event): event is SseEvent => event !== null);
    }

    /** The complete response: a last block without the closing blank line still counts. */
    finish(fullText: string): SseEvent[] {
        const events = this.read(fullText);
        const last = parseBlock(this.rest.replace(/\r$/, ""));
        this.rest = "";
        return last ? [...events, last] : events;
    }
}

function parseBlock(block: string): SseEvent | null {
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
        if (line === "" || line.startsWith(":")) {
            continue;
        }
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (field === "event") {
            event = value;
        } else if (field === "data") {
            data.push(value);
        }
    }
    return data.length > 0 ? {event, data: data.join("\n")} : null;
}
