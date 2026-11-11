import {SoftwarePrompt} from "../models/mcp.model";
import {softwarePromptLabel, softwarePromptValues} from "./software-prompt.util";

describe("software-prompt.util", () => {
    const prompt: SoftwarePrompt = {
        id: 1,
        software: "Buchhaltung",
        name: "create_test_program",
        title: "Prüfprogramm erstellen",
        description: "Creates a test program",
        arguments: [
            {name: "package_name", description: "The Java package", required: true},
            {name: "comment", description: "Optional remark", required: false},
        ],
    };

    it("names a prompt by its title, else by its name", () => {
        expect(softwarePromptLabel(prompt)).toBe("Prüfprogramm erstellen");
        expect(softwarePromptLabel({...prompt, title: null})).toBe("create_test_program");
        expect(softwarePromptLabel({...prompt, title: "  "})).toBe("create_test_program");
    });

    it("sends the prompt's arguments trimmed, leaves out empty ones and values for no argument", () => {
        expect(softwarePromptValues(prompt, {package_name: "  com.example.demo ", comment: "  ", other: "x"})).toEqual({
            package_name: "com.example.demo",
        });
    });
});
