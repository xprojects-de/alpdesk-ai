import {
    RunState,
    StepType,
    TaskCheckResult,
    TaskDraftRequest,
    TaskError,
    TaskRunDocument,
    TaskRunRow,
    TaskRunStep,
    TaskStepView,
    TaskToolCall,
} from "../models/task.model";

/** A key and its values for the transloco pipe. */
export interface Text {
    key: string;
    params?: Record<string, string>;
}

/** How a trigger reads in the list: "alle 5 h", "Zeitplan 0 30 6 * * MON-FRI"; none: by hand only. */
export function triggerTexts(triggers: string[]): Text[] {
    if (triggers.length === 0) {
        return [{key: "tasks.trigger.manual"}];
    }
    return triggers.map((trigger): Text =>
        trigger.startsWith("every ")
            ? {key: "tasks.trigger.every", params: {every: trigger.substring(6)}}
            : {key: "tasks.trigger.cron", params: {cron: trigger.replace(/^cron /, "")}},
    );
}

/** A run's state as a chip: ok, warn, err or info. */
export function runChip(state: RunState): {chip: string; label: string} {
    const chip = {DONE: "ok", UNCLEAR: "warn", FAILED: "err", CANCELLED: "info", QUEUED: "info", RUNNING: "info"}[
        state
    ];
    return {chip, label: "tasks.runState." + state};
}

/** A check point's result as a chip - a report found is no problem: it is shown as a report, not as a finding. */
export function checkChip(check: TaskCheckResult): {chip: string; label: string} {
    if (check.status === "finding" && check.severity === "REPORT") {
        return {chip: "info", label: "tasks.checkStatus.report"};
    }
    const chip = {ok: "ok", unclear: "info", finding: check.severity === "ERROR" ? "err" : "warn"}[check.status];
    return {chip, label: "tasks.checkStatus." + check.status};
}

/** The values of a failed step's error (its result's errorParams) - its code is translated with them. */
export function errorParams(step: TaskRunStep | undefined): Record<string, string> {
    const params = step?.result?.["errorParams"];
    return params && typeof params === "object" ? (params as Record<string, string>) : {};
}

/** The run's error is its last failed step's. */
export function failedStepParams(steps: TaskRunStep[]): Record<string, string> {
    return errorParams([...steps].reverse().find(step => step.state === "FAILED"));
}

export function isOpen(state: RunState): boolean {
    return state === "QUEUED" || state === "RUNNING";
}

/**
 * The navigation state that brings an imported or duplicated text to the editor's "new" route. Without it, "New
 * task" starts from the template - a draft left in the slice by an earlier visit does not come back.
 */
export const DRAFT_NAVIGATION = {taskDraft: true};

export function startsWithDraft(navigationState: unknown): boolean {
    return (navigationState as {taskDraft?: unknown} | null)?.taskDraft === true;
}

/** The short line under a step in the graph, in the frontend's language. */
export function aboutText(step: TaskStepView): Text {
    const details = step.details;
    switch (step.type) {
        case "read_files":
            return {
                key: details["onlyNew"] === "true" ? "tasks.about.readNew" : "tasks.about.read",
                params: {store: details["store"], pattern: details["pattern"]},
            };
        case "read_documents":
            return {
                key: details["subfolders"] === "true" ? "tasks.about.readDocumentsDeep" : "tasks.about.readDocuments",
                params: {store: details["store"], pattern: details["pattern"]},
            };
        case "extract":
            return {key: "tasks.about.extract", params: {model: details["model"], fields: details["fields"]}};
        case "call_tool":
            return {
                key: step.input.length === 0 ? "tasks.about.callToolSource" : "tasks.about.callTool",
                params: {connection: details["connection"], tool: details["tool"]},
            };
        case "call_agent":
            return {key: "tasks.about.callAgent", params: {agent: details["agent"], skill: details["skill"] ?? ""}};
        case "write_file":
            return {
                key: details["mode"] === "replace" ? "tasks.about.writeFileReplace" : "tasks.about.writeFile",
                params: {store: details["store"], file: details["file"]},
            };
        case "agent":
            return {key: "tasks.about.agent", params: {model: details["model"], count: details["checks"]}};
        case "verify":
            return {key: "tasks.about.verify", params: {of: details["of"]}};
        case "prompt":
            // No model: an assistant's prompt of a tenant answering through its agent - the agent answers.
            if (!details["model"]) {
                return details["choices"]
                    ? {key: "tasks.about.promptAgentChoices", params: {choices: details["choices"]}}
                    : {key: "tasks.about.promptAgent"};
            }
            return details["choices"]
                ? {key: "tasks.about.promptChoices", params: {model: details["model"], choices: details["choices"]}}
                : {key: "tasks.about.prompt", params: {model: details["model"]}};
        default:
            return details["to"]
                ? {key: "tasks.about.mailTo", params: {to: details["to"]}}
                : {key: "tasks.about.mailNotify"};
    }
}

/** The kind of a step, for its colour in the graph. */
export function stepKind(type: StepType): "fixed" | "agent" | "output" {
    switch (type) {
        case "agent":
        case "verify":
        case "prompt":
        case "extract":
            return "agent";
        case "send_mail":
        case "write_file":
            return "output";
        default:
            return "fixed";
    }
}

/** The columns of a step's rows: every field that has a value in one of them, in the order they first come. */
export function rowColumns(rows: TaskRunRow[]): string[] {
    const columns: string[] = [];
    for (const row of rows) {
        for (const name of Object.keys(row.values)) {
            if (!columns.includes(name)) {
                columns.push(name);
            }
        }
    }
    return columns;
}

/** A call_tool step's calls, as its result lists them. */
export function toolCalls(step: TaskRunStep): TaskToolCall[] {
    const calls = step.result?.["calls"];
    return Array.isArray(calls) ? (calls as TaskToolCall[]) : [];
}

/** A read_documents step's documents, as its result lists them. */
export function runDocuments(step: TaskRunStep): TaskRunDocument[] {
    const documents = step.result?.["documents"];
    return Array.isArray(documents) ? (documents as TaskRunDocument[]) : [];
}

export interface GraphEdge {
    from: string;
    to: string;
    /** The condition on the edge - an edge's own, or the target step's "if" on the way to it. */
    when: string | null;
    /** Back to an earlier step or itself: dashed, with its limit. */
    back: boolean;
    maxRounds: number | null;
}

/**
 * The graph's edges: every entry of a step's "next", then - unless an entry without a condition ends the step - the way
 * on to the step after it, with that step's condition.
 */
export function graphEdges(steps: TaskStepView[]): GraphEdge[] {
    const index = new Map(steps.map((step, i) => [step.id, i]));
    const edges: GraphEdge[] = [];
    steps.forEach((step, i) => {
        for (const edge of step.next) {
            const target = index.get(edge.to) ?? -1;
            edges.push({from: step.id, to: edge.to, when: edge.when, back: target <= i, maxRounds: edge.maxRounds});
        }
        const alwaysElsewhere = step.next.some(edge => edge.when === null);
        const following = steps[i + 1];
        if (following && !alwaysElsewhere) {
            edges.push({from: step.id, to: following.id, when: following.when, back: false, maxRounds: null});
        }
    });
    return edges;
}

/** The lines of each step in the text: from its first line to the line before the next step (or the text's end). */
export function stepRanges(steps: TaskStepView[], lineCount: number): Map<string, {from: number; to: number}> {
    const ranges = new Map<string, {from: number; to: number}>();
    const sorted = [...steps].sort((a, b) => a.line - b.line);
    sorted.forEach((step, i) => {
        const next = sorted[i + 1];
        ranges.set(step.id, {from: step.line, to: next ? next.line - 1 : lineCount});
    });
    return ranges;
}

/** The step a line belongs to - for marking the node of a mistake; null before the first step. */
export function stepAt(line: number, ranges: Map<string, {from: number; to: number}>): string | null {
    for (const [id, range] of ranges) {
        if (line >= range.from && line <= range.to) {
            return id;
        }
    }
    return null;
}

/** The backend's error code as a translation key; the message is the fallback where the frontend has none. */
export function errorText(error: TaskError): Text {
    return {key: "errors.backend." + error.code, params: error.params};
}

/** All tokens of a run or a round - the cached ones included, which the provider bills at their own price. */
export function allTokens(
    spent: Pick<TaskRunStep, "inputTokens" | "outputTokens" | "cacheReadTokens" | "cacheWriteTokens">,
): number {
    return spent.inputTokens + spent.outputTokens + spent.cacheReadTokens + spent.cacheWriteTokens;
}

/** A run's cost as the usage page shows an amount: cents, and up to four digits for the small ones of one step. */
export const COST_DIGITS = {minimumFractionDigits: 2, maximumFractionDigits: 4};

/** How each step of a run ended: its last round's state and choice, how many rounds it ran, its tokens together. */
export function nodeStates(
    steps: TaskRunStep[],
): Map<string, {state: string; rounds: number; tokens: number; choice: string | null}> {
    const states = new Map<string, {state: string; rounds: number; tokens: number; choice: string | null}>();
    for (const step of steps) {
        const before = states.get(step.stepId);
        const choice = step.result?.["choice"];
        states.set(step.stepId, {
            state: step.state,
            rounds: Math.max(before?.rounds ?? 0, step.round),
            tokens: (before?.tokens ?? 0) + allTokens(step),
            choice: typeof choice === "string" ? choice : null,
        });
    }
    return states;
}

/**
 * The lines of a draft that are new against the text before: each line of the draft not left over from the old text
 * (a line kept twice counts twice) - for marking what a draft changed.
 */
export function changedLines(before: string, after: string): number[] {
    const left = new Map<string, number>();
    for (const line of before.split("\n")) {
        left.set(line, (left.get(line) ?? 0) + 1);
    }
    const changed: number[] = [];
    after.split("\n").forEach((line, index) => {
        const count = left.get(line) ?? 0;
        if (count > 0) {
            left.set(line, count - 1);
        } else {
            changed.push(index + 1);
        }
    });
    return changed;
}

/** A new task's text: the log check, with the tenant's first file store and chat model filled in. */
export function taskTemplate(store: string | null, model: string | null): string {
    return `version: 1
name: Log check
# Who hears of a run that failed or stayed unclear.
notify: [it@example.com]
triggers:
  - type: schedule
    every: 5h
steps:
  - id: logs
    type: read_files
    store: ${store ?? "<file store>"}
    pattern: "{yyyy-MM-dd}_*.log"
    days: 2
    include: [ERROR, WARN]
  - id: check
    type: agent
    model: ${model ?? "<chat model>"}
    input: [logs]
    checks:
      - name: Interface
        severity: error
        instruction: Were there connection losses or timeouts to interface X?
  - id: verify
    type: verify
    of: check
    next:
      - if: verify.unclear > 0
        to: check
        maxRounds: 2
  - id: mail
    type: send_mail
    if: verify.errors > 0 || verify.warnings > 0
    input: [verify]
    subject: Findings in the log files
`;
}

export const MAX_DRAFT_DESCRIPTION = 4000;

/** What "Draft with AI" sends: the description trimmed, the text to change (none for a new task), the model. */
export function draftRequest(description: string, source: string | null, modelId: number | null): TaskDraftRequest {
    return {description: description.trim(), source: source?.trim() ? source : null, modelId};
}
