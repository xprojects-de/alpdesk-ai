import {ToolStep} from "../models/chat.model";

/** A tool step as the chat shows it: an icon and a translation key with its params. */
export interface ToolStepView {
    icon: string;
    key: string;
    params: Record<string, string>;
}

/** The application's own tools (services.tools in the backend), by name. */
const OWN_TOOLS: Record<string, {icon: string; key: string}> = {
    similarity_search: {icon: "search", key: "chat.tools.similaritySearch"},
    read_document: {icon: "article", key: "chat.tools.readDocument"},
    read_file: {icon: "attach_file", key: "chat.tools.readFile"},
    search_file: {icon: "find_in_page", key: "chat.tools.searchFile"},
    read_file_lines: {icon: "subject", key: "chat.tools.readFileLines"},
    create_file: {icon: "note_add", key: "chat.tools.createFile"},
    create_image: {icon: "image", key: "chat.tools.createImage"},
    run_python: {icon: "code", key: "chat.tools.runPython"},
    run_shell: {icon: "terminal", key: "chat.tools.runShell"},
    view_images: {icon: "photo_library", key: "chat.tools.viewImages"},
    hand_out_file: {icon: "file_download", key: "chat.tools.handOutFile"},
    web_search: {icon: "travel_explore", key: "chat.tools.webSearch"},
};

/** How a step is shown: a connected software's tool by both names, an unknown tool of ours by its name. */
export function toolStepView(step: ToolStep): ToolStepView {
    if (step.software) {
        return {icon: "extension", key: "chat.tools.software", params: {software: step.software, tool: step.name}};
    }
    const own = OWN_TOOLS[step.name];
    return own ? {...own, params: {}} : {icon: "build", key: "chat.tools.other", params: {name: step.name}};
}

/** The steps' views without repeats, in the order first called - the collapsed line names each kind once. */
export function distinctToolViews(steps: ToolStep[]): ToolStepView[] {
    const seen = new Set<string>();
    const views: ToolStepView[] = [];
    for (const step of steps) {
        const view = toolStepView(step);
        const id = view.key + JSON.stringify(view.params);
        if (!seen.has(id)) {
            seen.add(id);
            views.push(view);
        }
    }
    return views;
}
