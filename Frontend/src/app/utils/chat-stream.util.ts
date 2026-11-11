import {ChatResponse, ChatStreamEvent} from "../models/chat.model";
import {SseEvent} from "./sse.util";

/** The chat stream's event of a server-sent event; null for an event name it does not know. */
export function toChatStreamEvent(event: SseEvent): ChatStreamEvent | null {
    const data = JSON.parse(event.data);
    switch (event.event) {
        case "sources":
            return {type: "sources", sources: data.sources ?? []};
        case "delta":
            return {type: "delta", text: data.text ?? ""};
        case "thinking":
            return {type: "thinking", text: data.text ?? ""};
        case "tool":
            return {
                type: "tool",
                step: {name: data.name ?? "", software: data.software ?? null},
            };
        case "done":
            return {type: "done", response: data as ChatResponse};
        case "error":
            return {type: "error", message: data.message ?? "", code: data.code, params: data.params};
        default:
            return null;
    }
}
