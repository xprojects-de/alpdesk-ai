import {changeableOf, noDoor, KeyChoices, keyRequest, keySummary} from "./key-form.util";

describe("key-form.util", () => {
    const all: KeyChoices = {
        areas: true,
        software: true,
        tasks: true,
        webSearch: true,
        keepsHistory: true,
        description: true,
        doors: false,
        changing: false,
    };
    const value = {
        name: "  ERP Produktion ",
        areaIds: [2],
        connectionIds: [5],
        changingConnectionIds: [] as number[],
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
            changing: true,
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
            changing: [],
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

    it("sends the software an MCP key may change in - only of its software and going directly to MCP clients", () => {
        const mcpKey = {...all, keepsHistory: false, doors: true, changing: true};
        const chosen = {...value, connectionIds: [5, 6, 9], changingConnectionIds: [5, 6, 7]};
        expect(keyRequest(chosen, mcpKey, [5, 7, 9]).changingConnectionIds).toEqual([5]);
        expect(keyRequest({...chosen, changingConnectionIds: []}, mcpKey, [5]).changingConnectionIds).toEqual([]);
        expect(keyRequest(chosen, all, [5])).not.toHaveProperty("changingConnectionIds");
        expect(keyRequest(chosen, {...mcpKey, software: false}, [5])).not.toHaveProperty("changingConnectionIds");
        expect(changeableOf([5, 6], [5, 6], [6])).toEqual([6]);
    });

    it("names the software a key may change in", () => {
        const known = {
            areas: [],
            software: [
                {id: 5, name: "Ticketsystem"},
                {id: 6, name: "Lager"},
            ],
            tasks: [],
        };
        const key = {areaIds: [], connectionIds: [5, 6], changingConnectionIds: [5], taskIds: [], webSearch: false};
        expect(keySummary(key, known, all).changing).toEqual(["Ticketsystem"]);
        expect(keySummary({...key, changingConnectionIds: undefined}, known, all).changing).toEqual([]);
    });
});
