import {appBasePath} from "./base-path.util";

describe("appBasePath", () => {
    it.each([
        ["https://ai.example.com/", ""],
        ["https://intranet.example.com/alpdesk-ai/", "/alpdesk-ai"],
        ["https://intranet.example.com/tools/alpdesk-ai/", "/tools/alpdesk-ai"],
        ["http://localhost:19100/", ""],
    ])("%s -> '%s'", (baseUri, path) => {
        expect(appBasePath(baseUri)).toBe(path);
    });
});
