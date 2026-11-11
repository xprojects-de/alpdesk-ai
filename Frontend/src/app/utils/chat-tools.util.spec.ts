import {ToolStep} from "../models/chat.model";
import {distinctToolViews, toolStepView} from "./chat-tools.util";

const step = (name: string, software: string | null = null): ToolStep => ({name, software});

describe("toolStepView", () => {
    it("shows the application's own tools by an icon and a text of their own", () => {
        expect(toolStepView(step("similarity_search"))).toEqual({
            icon: "search",
            key: "chat.tools.similaritySearch",
            params: {},
        });
        expect(toolStepView(step("run_python")).key).toBe("chat.tools.runPython");
        expect(toolStepView(step("web_search")).key).toBe("chat.tools.webSearch");
    });

    it("shows a connected software's tool by the software's and the tool's name", () => {
        expect(toolStepView(step("find_invoice", "Buchhaltung"))).toEqual({
            icon: "extension",
            key: "chat.tools.software",
            params: {software: "Buchhaltung", tool: "find_invoice"},
        });
    });

    it("shows a tool it does not know by its name", () => {
        expect(toolStepView(step("send_mail"))).toEqual({
            icon: "build",
            key: "chat.tools.other",
            params: {name: "send_mail"},
        });
    });
});

describe("distinctToolViews", () => {
    it("names each kind of step once, in the order first called", () => {
        const views = distinctToolViews([
            step("similarity_search"),
            step("read_document"),
            step("similarity_search"),
            step("find_invoice", "Buchhaltung"),
            step("find_invoice", "Lager"),
        ]);
        expect(views.map(view => view.key)).toEqual([
            "chat.tools.similaritySearch",
            "chat.tools.readDocument",
            "chat.tools.software",
            "chat.tools.software",
        ]);
    });
});
