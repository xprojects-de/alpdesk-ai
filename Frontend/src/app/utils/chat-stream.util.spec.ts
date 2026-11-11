import {toChatStreamEvent} from "./chat-stream.util";

describe("toChatStreamEvent", () => {
    it("reads the events of the chat stream", () => {
        expect(toChatStreamEvent({event: "sources", data: '{"sources":["a.pdf"]}'})).toEqual({
            type: "sources",
            sources: ["a.pdf"],
        });
        expect(toChatStreamEvent({event: "delta", data: '{"text":"Hallo "}'})).toEqual({type: "delta", text: "Hallo "});
        expect(toChatStreamEvent({event: "thinking", data: '{"text":"Erst prüfen"}'})).toEqual({
            type: "thinking",
            text: "Erst prüfen",
        });
        expect(toChatStreamEvent({event: "tool", data: '{"name":"similarity_search"}'})).toEqual({
            type: "tool",
            step: {name: "similarity_search", software: null},
        });
        expect(toChatStreamEvent({event: "tool", data: '{"name":"find_invoice","software":"Buchhaltung"}'})).toEqual({
            type: "tool",
            step: {name: "find_invoice", software: "Buchhaltung"},
        });
        expect(toChatStreamEvent({event: "error", data: '{"message":"Provider failed"}'})).toEqual({
            type: "error",
            message: "Provider failed",
        });
        expect(
            toChatStreamEvent({
                event: "error",
                data: '{"message":"Too large","code":"chatFile.tooLarge","params":{"max":"50"}}',
            }),
        ).toEqual({type: "error", message: "Too large", code: "chatFile.tooLarge", params: {max: "50"}});
        const done = toChatStreamEvent({event: "done", data: '{"conversation":{"id":7},"sources":[]}'});
        expect(done?.type === "done" && done.response.conversation.id).toBe(7);
    });

    it("ignores events it does not know", () => {
        expect(toChatStreamEvent({event: "ping", data: "{}"})).toBeNull();
    });
});
