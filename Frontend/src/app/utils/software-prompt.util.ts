import {SoftwarePrompt} from "../models/mcp.model";

/** What a prompt is called in the chat: its title, else its name. */
export function softwarePromptLabel(prompt: SoftwarePrompt): string {
    return prompt.title?.trim() || prompt.name;
}

/**
 * The values a prompt's form sends: each of the prompt's arguments with what was typed, trimmed - an empty one left
 * out, so the software takes its own default for an optional argument.
 */
export function softwarePromptValues(prompt: SoftwarePrompt, form: Record<string, string>): Record<string, string> {
    const values: Record<string, string> = {};
    for (const argument of prompt.arguments) {
        const value = (form[argument.name] ?? "").trim();
        if (value) {
            values[argument.name] = value;
        }
    }
    return values;
}
