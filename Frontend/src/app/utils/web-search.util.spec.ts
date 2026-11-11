import {AiModel} from "../models/ai.model";
import {webSearchAmong, webSearchAssigned} from "./web-search.util";

describe("web-search.util", () => {
    const entry = (id: number, kind: AiModel["kind"], active = true) => ({id, kind, active}) as AiModel;

    it("finds the web search among a tenant's own models", () => {
        expect(webSearchAmong([{kind: "CHAT"}, {kind: "WEB_SEARCH"}])).toBe(true);
        expect(webSearchAmong([{kind: "CHAT"}, {kind: "EMBEDDING"}])).toBe(false);
        expect(webSearchAmong([])).toBe(false);
    });

    it("finds it for the admin only where an active entry is assigned to the tenant", () => {
        const catalog = [entry(1, "CHAT"), entry(2, "WEB_SEARCH"), entry(3, "WEB_SEARCH", false)];

        expect(webSearchAssigned(catalog, [1, 2])).toBe(true);
        expect(webSearchAssigned(catalog, [1])).toBe(false);
        expect(webSearchAssigned(catalog, [1, 3])).toBe(false);
        expect(webSearchAssigned(catalog, null)).toBe(false);
    });
});
