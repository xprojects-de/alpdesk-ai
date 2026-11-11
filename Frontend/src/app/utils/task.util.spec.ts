import {describe, expect, it} from "vitest";
import {TaskRunStep, TaskStepView} from "../models/task.model";
import {layoutGraph} from "./task-graph.util";
import {
    aboutText,
    changedLines,
    checkChip,
    DRAFT_NAVIGATION,
    draftRequest,
    errorParams,
    failedStepParams,
    graphEdges,
    nodeStates,
    rowColumns,
    runChip,
    runDocuments,
    startsWithDraft,
    stepAt,
    stepKind,
    stepRanges,
    taskTemplate,
    toolCalls,
    triggerTexts,
} from "./task.util";

function step(id: string, line: number, extra: Partial<TaskStepView> = {}): TaskStepView {
    return {id, type: "read_files", line, input: [], when: null, next: [], details: {}, ...extra};
}

const logCheck: TaskStepView[] = [
    step("logs", 6),
    step("check", 13, {type: "agent", input: ["logs"]}),
    step("verify", 22, {type: "verify", next: [{when: "verify.unclear > 0", to: "check", maxRounds: 2}]}),
    step("mail", 28, {type: "send_mail", when: "verify.errors > 0"}),
];

describe("startsWithDraft", () => {
    it("is true only for the navigation of an import or a duplicate", () => {
        expect(startsWithDraft(DRAFT_NAVIGATION)).toBe(true);
        expect(startsWithDraft({navigationId: 3})).toBe(false);
        expect(startsWithDraft(null)).toBe(false);
        expect(startsWithDraft(undefined)).toBe(false);
    });
});

describe("triggerTexts", () => {
    it("reads every and cron, and says by hand without triggers", () => {
        expect(triggerTexts(["every 5h", "cron 0 30 6 * * MON-FRI"])).toEqual([
            {key: "tasks.trigger.every", params: {every: "5h"}},
            {key: "tasks.trigger.cron", params: {cron: "0 30 6 * * MON-FRI"}},
        ]);
        expect(triggerTexts([])).toEqual([{key: "tasks.trigger.manual"}]);
    });
});

describe("runChip", () => {
    it.each([
        ["DONE", "ok"],
        ["UNCLEAR", "warn"],
        ["FAILED", "err"],
        ["RUNNING", "info"],
        ["CANCELLED", "info"],
    ] as const)("%s is %s", (state, chip) => {
        expect(runChip(state)).toEqual({chip, label: "tasks.runState." + state});
    });
});

describe("checkChip", () => {
    it.each([
        ["ok", "ERROR", "ok", "tasks.checkStatus.ok"],
        ["finding", "ERROR", "err", "tasks.checkStatus.finding"],
        ["finding", "WARNING", "warn", "tasks.checkStatus.finding"],
        ["unclear", "WARNING", "info", "tasks.checkStatus.unclear"],
        ["finding", "REPORT", "info", "tasks.checkStatus.report"],
        ["ok", "REPORT", "ok", "tasks.checkStatus.ok"],
    ] as const)("%s of a %s check point is %s", (status, severity, chip, label) => {
        expect(checkChip({name: "A", severity, status, note: "", evidence: []})).toEqual({chip, label});
    });
});

describe("errorParams", () => {
    const run = (stepId: string, state: "DONE" | "FAILED", result: Record<string, unknown> | null): TaskRunStep => ({
        stepId,
        round: 1,
        state,
        result,
        errorCode: state === "FAILED" ? "task.x" : null,
        inputTokens: 0,
        outputTokens: 0,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        startedAt: "",
        endedAt: "",
        cost: null,
    });
    const failed = (stepId: string, result: Record<string, unknown> | null): TaskRunStep =>
        run(stepId, "FAILED", result);

    it("takes a failed step's values, and none where it kept none", () => {
        expect(errorParams(failed("cron", {errorParams: {step: "cron", lines: "0"}}))).toEqual({
            step: "cron",
            lines: "0",
        });
        expect(errorParams(failed("cron", null))).toEqual({});
        expect(errorParams(undefined)).toEqual({});
    });

    it("gives the run's error the values of its last failed step", () => {
        const done = run("logs", "DONE", {lines: 3});
        expect(failedStepParams([done, failed("cron", {errorParams: {min: "1"}})])).toEqual({min: "1"});
        expect(failedStepParams([done])).toEqual({});
    });
});

describe("stepKind", () => {
    it("colours agent and verify alike, mail as output, reading as fixed", () => {
        expect(
            ["read_files", "read_documents", "extract", "agent", "verify", "send_mail"].map(type =>
                stepKind(type as never),
            ),
        ).toEqual(["fixed", "fixed", "agent", "agent", "agent", "output"]);
        expect(stepKind("write_file")).toBe("output");
    });
});

describe("rowColumns", () => {
    it("names every field with a value, in the order they first come", () => {
        const rows = [
            {stepId: "rows", source: "a.pdf#1", values: {date: "2026-01-24", gross: 119}, unclear: null},
            {stepId: "rows", source: "b.pdf#2", values: {}, unclear: "The document is empty"},
            {stepId: "rows", source: "c.pdf#3", values: {partner: "Musterfirma", date: "2026-01-25"}, unclear: null},
        ];
        expect(rowColumns(rows)).toEqual(["date", "gross", "partner"]);
        expect(rowColumns([])).toEqual([]);
    });
});

describe("toolCalls", () => {
    it("lists a call_tool step's calls - none for another step", () => {
        const calls = [{source: "a.pdf#1", failed: false, answer: "Booked"}];
        expect(
            toolCalls({stepId: "send", round: 1, state: "DONE", result: {sent: 1, failed: 0, calls}} as never),
        ).toEqual(calls);
        expect(toolCalls({stepId: "x", round: 1, state: "DONE", result: {rows: 1}} as never)).toEqual([]);
    });
});

describe("runDocuments", () => {
    it("lists a read_documents step's documents - none for another step", () => {
        const documents = [
            {name: "a.pdf", source: "a.pdf#1", fileId: 3},
            {name: "b.png", source: "b.png#2", problem: "x"},
        ];
        const round = {stepId: "d", round: 1, state: "DONE", result: {files: 2, documents}} as never;
        expect(runDocuments(round)).toEqual(documents);
        expect(runDocuments({stepId: "x", round: 1, state: "DONE", result: {files: 1, lines: 3}} as never)).toEqual([]);
    });
});

describe("aboutText", () => {
    it("writes each type's line from its details", () => {
        expect(aboutText(step("a", 1, {details: {store: "Logs", pattern: "*.log", onlyNew: "true"}}))).toEqual({
            key: "tasks.about.readNew",
            params: {store: "Logs", pattern: "*.log"},
        });
        expect(aboutText(step("b", 1, {type: "agent", details: {model: "M", checks: "2"}}))).toEqual({
            key: "tasks.about.agent",
            params: {model: "M", count: "2"},
        });
        expect(aboutText(step("c", 1, {type: "send_mail", details: {to: ""}}))).toEqual({
            key: "tasks.about.mailNotify",
        });
        expect(aboutText(step("d", 1, {type: "prompt", details: {model: "M", choices: "a, b"}}))).toEqual({
            key: "tasks.about.promptChoices",
            params: {model: "M", choices: "a, b"},
        });
        expect(
            aboutText(
                step("f", 1, {
                    type: "read_documents",
                    details: {store: "Ablage", pattern: "*.pdf", subfolders: "true"},
                }),
            ),
        ).toEqual({key: "tasks.about.readDocumentsDeep", params: {store: "Ablage", pattern: "*.pdf"}});
        expect(
            aboutText(
                step("i", 1, {type: "call_tool", input: [], details: {connection: "Faktura", tool: "list_invoices"}}),
            ),
        ).toEqual({
            key: "tasks.about.callToolSource",
            params: {connection: "Faktura", tool: "list_invoices"},
        });
        expect(
            aboutText(step("j", 1, {type: "call_agent", details: {agent: "Buchungsagent", skill: "rechnungen"}})),
        ).toEqual({key: "tasks.about.callAgent", params: {agent: "Buchungsagent", skill: "rechnungen"}});
        expect(
            aboutText(step("h", 1, {type: "write_file", details: {store: "Ablage", file: "a.csv", mode: "replace"}})),
        ).toEqual({key: "tasks.about.writeFileReplace", params: {store: "Ablage", file: "a.csv"}});
        expect(aboutText(step("g", 1, {type: "extract", details: {model: "M", fields: "date, gross"}}))).toEqual({
            key: "tasks.about.extract",
            params: {model: "M", fields: "date, gross"},
        });
        expect(aboutText(step("e", 1, {type: "prompt", details: {model: "M", choices: ""}}))).toEqual({
            key: "tasks.about.prompt",
            params: {model: "M"},
        });
        expect(aboutText(step("h", 1, {type: "prompt", details: {model: "", choices: ""}}))).toEqual({
            key: "tasks.about.promptAgent",
        });
        expect(aboutText(step("i", 1, {type: "prompt", details: {model: "", choices: "a, b"}}))).toEqual({
            key: "tasks.about.promptAgentChoices",
            params: {choices: "a, b"},
        });
    });
});

describe("graphEdges", () => {
    it("adds the way on to the next step with its condition, and marks an edge back", () => {
        expect(graphEdges(logCheck)).toEqual([
            {from: "logs", to: "check", when: null, back: false, maxRounds: null},
            {from: "check", to: "verify", when: null, back: false, maxRounds: null},
            {from: "verify", to: "check", when: "verify.unclear > 0", back: true, maxRounds: 2},
            {from: "verify", to: "mail", when: "verify.errors > 0", back: false, maxRounds: null},
        ]);
    });

    it("takes no way on after an entry without a condition - the step always goes there", () => {
        const jumps = [step("a", 1, {next: [{when: null, to: "c", maxRounds: null}]}), step("b", 5), step("c", 9)];
        expect(graphEdges(jumps).filter(edge => edge.from === "a")).toEqual([
            {from: "a", to: "c", when: null, back: false, maxRounds: null},
        ]);
    });
});

describe("stepRanges and stepAt", () => {
    it("gives each step its lines up to the next step, and finds the step of a line", () => {
        const ranges = stepRanges(logCheck, 33);
        expect(ranges.get("check")).toEqual({from: 13, to: 21});
        expect(ranges.get("mail")).toEqual({from: 28, to: 33});
        expect(stepAt(15, ranges)).toBe("check");
        expect(stepAt(2, ranges)).toBeNull();
    });
});

describe("nodeStates", () => {
    it("keeps each step's last state, its rounds and its tokens together", () => {
        const run = (stepId: string, round: number, state: "DONE" | "SKIPPED" | "FAILED", tokens = 0): TaskRunStep => ({
            stepId,
            round,
            state,
            result: null,
            errorCode: null,
            inputTokens: tokens,
            outputTokens: 0,
            cacheReadTokens: 0,
            cacheWriteTokens: 0,
            startedAt: "",
            endedAt: "",
            cost: null,
        });
        const states = nodeStates([
            run("check", 1, "DONE", 100),
            run("check", 2, "FAILED", 50),
            run("mail", 1, "SKIPPED"),
        ]);
        expect(states.get("check")).toEqual({state: "FAILED", rounds: 2, tokens: 150, choice: null});
        expect(states.get("mail")).toEqual({state: "SKIPPED", rounds: 1, tokens: 0, choice: null});
    });
});

describe("draftRequest", () => {
    it("sends the description trimmed, the text only when there is one", () => {
        expect(draftRequest("  Pruefe die Logs  ", "  ", 3)).toEqual({
            description: "Pruefe die Logs",
            source: null,
            modelId: 3,
        });
        expect(draftRequest("x", "version: 1", null)).toEqual({description: "x", source: "version: 1", modelId: null});
    });
});

describe("changedLines", () => {
    it("marks the lines a draft wrote new, a kept line once for each time it was there", () => {
        expect(changedLines("a\nb\nc", "a\nB\nc\nd")).toEqual([2, 4]);
        expect(changedLines("x\nx", "x\nx\nx")).toEqual([3]);
        expect(changedLines("", "a")).toEqual([1]);
    });
});

describe("taskTemplate", () => {
    it("fills in the store and the model, or marks where they go", () => {
        expect(taskTemplate("Logdateien", "Claude Sonnet")).toContain("store: Logdateien");
        expect(taskTemplate("Logdateien", "Claude Sonnet")).toContain("model: Claude Sonnet");
        expect(taskTemplate(null, null)).toContain("store: <file store>");
    });
});

describe("layoutGraph", () => {
    it("places the steps top to bottom, the edge back left out", () => {
        const nodes = logCheck.map(s => ({id: s.id, width: 200, height: 56}));
        const positions = layoutGraph(nodes, graphEdges(logCheck));
        const ys = logCheck.map(s => positions.get(s.id)!.y);
        expect(ys).toEqual([...ys].sort((a, b) => a - b));
        expect(new Set(ys).size).toBe(4);
    });
});
