import {noDoor, KeyChoices, keyRequest, keySummary} from "./key-form.util";

describe("key-form.util", () => {
    const all: KeyChoices = {
        areas: true,
        software: true,
        tasks: true,
        webSearch: true,
        keepsHistory: true,
        description: true,
        doors: false,
    };
    const value = {
        name: "  ERP Produktion ",
        areaIds: [2],
        connectionIds: [5],
        taskIds: [7, 8],
        webSearch: true,
        keepsHistory: false,
        description: "  Wartung und Montage ",
        mcp: true,
        a2a: false,
    };

    it("sends every choice the dialog shows, the name trimmed", () => {
        expect(keyRequest(value, all)).toEqual({
            name: "ERP Produktion",
            areaIds: [2],
            connectionIds: [5],
            taskIds: [7, 8],
            webSearch: true,
            keepsHistory: false,
            description: "Wartung und Montage",
        });
    });

    it("sends an emptied description as empty - it removes the key's one", () => {
        expect(keyRequest({...value, description: "   "}, all).description).toBe("");
    });

    it("leaves out what it does not show - nothing on create, unchanged on a change", () => {
        const mcp: KeyChoices = {
            areas: true,
            software: false,
            tasks: false,
            webSearch: false,
            keepsHistory: false,
            description: false,
            doors: false,
        };
        expect(keyRequest(value, mcp)).toEqual({name: "ERP Produktion", areaIds: [2]});
    });

    it("sends empty lists to take every area, software or task off a key", () => {
        expect(keyRequest({...value, areaIds: [], connectionIds: [], taskIds: []}, all)).toEqual(
            expect.objectContaining({areaIds: [], connectionIds: [], taskIds: []}),
        );
    });

    it("summarises a key's rights by name in the order of the tenant's lists, unknown ids left out", () => {
        const known = {
            areas: [
                {id: 1, name: "Entwicklung"},
                {id: 2, name: "Kalkulation"},
            ],
            software: [{id: 5, name: "Buchhaltung"}],
            tasks: [
                {id: 7, name: "Lagerabgleich"},
                {id: 8, name: "Rechnungsprüfung"},
            ],
        };
        const key = {areaIds: [2, 1, 99], connectionIds: [5], taskIds: [8], webSearch: true};
        expect(keySummary(key, known, all)).toEqual({
            areas: ["Entwicklung", "Kalkulation"],
            software: ["Buchhaltung"],
            tasks: ["Rechnungsprüfung"],
            webSearch: true,
        });
        expect(keySummary(key, known, {...all, tasks: false, webSearch: false})).toEqual(
            expect.objectContaining({tasks: [], webSearch: false}),
        );
    });

    it("sends an MCP key's doors, and refuses a key opening none", () => {
        const mcpKey = {...all, keepsHistory: false, doors: true};
        expect(keyRequest({...value, a2a: true}, mcpKey)).toEqual(expect.objectContaining({mcp: true, a2a: true}));
        expect(noDoor({mcp: false, a2a: false}, mcpKey)).toBe(true);
        expect(noDoor({mcp: false, a2a: true}, mcpKey)).toBe(false);
        expect(noDoor({mcp: false, a2a: false}, all)).toBe(false);
    });
});
