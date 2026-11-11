import {SseReader} from "./sse.util";

describe("SseReader", () => {
    /** Feeds the chunks like XHR does: each call gets the whole text so far. */
    function readChunks(chunks: string[]) {
        const reader = new SseReader();
        let text = "";
        return chunks.flatMap(chunk => reader.read((text += chunk)));
    }

    it("reads several events from one chunk", () => {
        expect(readChunks(['event: sources\ndata: {"sources":[]}\n\nevent: delta\ndata: {"text":"Hal"}\n\n'])).toEqual([
            {event: "sources", data: '{"sources":[]}'},
            {event: "delta", data: '{"text":"Hal"}'},
        ]);
    });

    it("waits for a block split across chunks, even inside a line", () => {
        const events = readChunks(["event: del", 'ta\ndata: {"te', 'xt":"lo"}\n', "\nevent: done\n"]);
        expect(events).toEqual([{event: "delta", data: '{"text":"lo"}'}]);
    });

    it("handles CRLF, also when a chunk ends between \\r and \\n", () => {
        const events = readChunks(['event: delta\r\ndata: {"text":"a"}\r', "\n\r\n", 'data: {"text":"b"}\r\n\r\n']);
        expect(events).toEqual([
            {event: "delta", data: '{"text":"a"}'},
            {event: "message", data: '{"text":"b"}'},
        ]);
    });

    it("ignores comments and joins data lines", () => {
        expect(readChunks([": keep-alive\n\nevent: x\ndata: one\ndata: two\n\n"])).toEqual([
            {event: "x", data: "one\ntwo"},
        ]);
    });

    it("takes a last block without the closing blank line at the end of the response", () => {
        const reader = new SseReader();
        expect(reader.read('event: done\ndata: {"a":1}')).toEqual([]);
        expect(reader.finish('event: done\ndata: {"a":1}')).toEqual([{event: "done", data: '{"a":1}'}]);
    });
});
