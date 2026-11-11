/** A scheduled task (docs/architecture/tasks.md): its text as written, its steps as compiled, its runs. */

export type RunState = "QUEUED" | "RUNNING" | "DONE" | "UNCLEAR" | "FAILED" | "CANCELLED";
export type StartedBy = "ADMIN" | "TENANT_ADMIN" | "SCHEDULE" | "API" | "MCP" | "A2A";
export type StepType =
    | "read_files"
    | "read_documents"
    | "extract"
    | "write_file"
    | "call_tool"
    | "call_agent"
    | "agent"
    | "verify"
    | "prompt"
    | "send_mail";

export interface TaskRunSummary {
    id: number;
    taskVersion: number;
    state: RunState;
    startedBy: StartedBy;
    startedByUser: number | null;
    startedByKey: number | null;
    cancelRequested: boolean;
    errorCode: string | null;
    errorMessage: string | null;
    mailFailed: boolean;
    inputTokens: number;
    outputTokens: number;
    /** Read from / written to the provider's cache - apart from inputTokens. */
    cacheReadTokens: number;
    cacheWriteTokens: number;
    createdAt: string;
    startedAt: string | null;
    endedAt: string | null;
    /** What its steps cost, as the usage recorded it (a run from before: at the catalog's price); null in the task list for those. */
    cost: number | null;
    /** A step used tokens whose model is no longer in the catalog - the cost is at least this much. */
    costPartial: boolean;
}

export interface TaskSummary {
    id: number;
    name: string;
    active: boolean;
    version: number;
    /** As the backend writes them: "every 5h", "cron 0 30 6 * * MON-FRI". */
    triggers: string[];
    nextRunAt: string | null;
    lastRun: TaskRunSummary | null;
    createdAt: string;
    updatedAt: string;
}

export interface TaskEdge {
    /** null: always (the else). */
    when: string | null;
    to: string;
    maxRounds: number | null;
}

/** A step as the graph beside the text shows it. */
export interface TaskStepView {
    id: string;
    type: StepType;
    /** Where it begins in the text (from 1). */
    line: number;
    input: string[];
    when: string | null;
    next: TaskEdge[];
    /** What it works with, by type: store, pattern, onlyNew; model, checks; of; model, choices; to ("" - the notify list). */
    details: Record<string, string>;
}

/** A key that may start the task: the client software's API key or an MCP key. */
export interface TaskKey {
    kind: "API" | "MCP";
    name: string;
}

export interface Task extends TaskSummary {
    source: string;
    steps: TaskStepView[];
    /** The keys that may start it - chosen at the keys, shown here. */
    keys: TaskKey[];
}

/** A mistake in a task's text: line and column from 1 (0: the whole task), a backend error code with its values. */
export interface TaskError {
    line: number;
    column: number;
    code: string;
    params: Record<string, string>;
    message: string;
}

export interface TaskValidation {
    valid: boolean;
    errors: TaskError[];
    steps: TaskStepView[];
}

export interface TaskRequest {
    source: string;
    active?: boolean;
}

export type StepState = "DONE" | "SKIPPED" | "FAILED";

export interface TaskRunStep {
    stepId: string;
    round: number;
    state: StepState;
    /** The step's structured result: counts, check points with evidence, files read. */
    result: Record<string, unknown> | null;
    errorCode: string | null;
    inputTokens: number;
    outputTokens: number;
    cacheReadTokens: number;
    cacheWriteTokens: number;
    startedAt: string;
    endedAt: string;
    /** What the round's calls cost; null: no model asked, or a round from before whose model is gone. */
    cost: number | null;
}

/** What a prompt step answered in one round, and the word it picked from its choices. */
export interface TaskAnswer {
    stepId: string;
    round: number;
    choice: string | null;
    text: string;
}

/** A row an extract step took out of one document: the fields' values by name, and why it is unclear (null: clear). */
export interface TaskRunRow {
    stepId: string;
    /** The document's path and the checksum of its content. */
    source: string;
    values: Record<string, unknown>;
    unclear: string | null;
}

/** A call of a call_tool step: the row's source, whether the tool refused it, its answer. */
export interface TaskToolCall {
    source: string;
    failed: boolean;
    answer: string;
}

/** A document a read_documents step took: its run file, or why it could not be read. */
export interface TaskRunDocument {
    name: string;
    source: string;
    fileId?: number;
    problem?: string;
}

export interface TaskRunDetail {
    run: TaskRunSummary;
    steps: TaskRunStep[];
    definition: TaskStepView[];
    answers: TaskAnswer[];
    rows: TaskRunRow[];
}

/** What a draft with AI is to do: the description; the current text when a task is changed; the model. */
export interface TaskDraftRequest {
    description: string;
    source: string | null;
    modelId: number | null;
}

/** A draft - never saved: its text, the mistakes the check left, its steps, the model's rounds. */
export interface TaskDraft extends TaskValidation {
    source: string;
    rounds: number;
}

export interface TaskDraftModels {
    models: {id: number; displayName: string}[];
    defaultModelId: number | null;
}

/** A check point as an agent or verify step reports it. */
export interface TaskCheckResult {
    name: string;
    severity: "ERROR" | "WARNING" | "REPORT";
    status: "ok" | "finding" | "unclear";
    note: string;
    evidence: {file: string; line: number; text: string}[];
}
