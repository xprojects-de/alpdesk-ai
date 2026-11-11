import {Component, computed, inject, input, OnDestroy, OnInit, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoCurrencyPipe, TranslocoDatePipe, TranslocoDecimalPipe} from "@jsverse/transloco-locale";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TaskAnswer, TaskCheckResult, TaskRunDocument, TaskRunStep, TaskToolCall} from "../../models/task.model";
import {TenantScope} from "../../models/tenant.model";
import * as TaskActions from "../../store/task/task.actions";
import * as TaskSelectors from "../../store/task/task.selectors";
import {
    allTokens,
    checkChip,
    COST_DIGITS,
    errorParams,
    failedStepParams,
    isOpen,
    nodeStates,
    rowColumns,
    runChip,
    runDocuments,
    toolCalls,
} from "../../utils/task.util";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {TaskGraphComponent} from "../shared/task-graph/task-graph.component";

/**
 * One run of a task (docs/architecture/tasks.md "The run view"): the graph of the definition it ran with, every step
 * with its state, rounds and tokens; a click shows what the step gave - the files and lines read, the check points with
 * their evidence lines, the failure. Asked again every 2 s while the run is open.
 */
@Component({
    selector: "app-task-run",
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressBarModule,
        PageHeaderComponent,
        TaskGraphComponent,
        TranslocoPipe,
        TranslocoDatePipe,
        TranslocoDecimalPipe,
        TranslocoCurrencyPipe,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'tasks.run.title' | transloco: {id: runId()}"
                [subtitle]="subtitle() ? ('tasks.run.subtitle' | transloco: subtitle()!) : null"
                [backLink]="taskLink()"
                [backLabel]="'tasks.run.back' | transloco"
            >
                @if (detail(); as detail) {
                    @if (open()) {
                        <button mat-stroked-button (click)="cancel()" [disabled]="detail.run.cancelRequested">
                            <mat-icon>stop</mat-icon>
                            {{ "tasks.runs.cancel" | transloco }}
                        </button>
                    }
                }
            </app-page-header>

            @if (detail(); as detail) {
                @let chip = chipOf(detail.run.state);
                <div class="app-summary">
                    <div>
                        <span class="label">{{ "tasks.runs.state" | transloco }}</span>
                        <span class="app-chip" [class]="chip.chip">{{ chip.label | transloco }}</span>
                    </div>
                    <div>
                        <span class="label">{{ "tasks.runs.by" | transloco }}</span>
                        <span>{{ "tasks.startedBy." + detail.run.startedBy | transloco }}</span>
                    </div>
                    <div>
                        <span class="label">{{ "tasks.runs.started" | transloco }}</span>
                        <span>{{
                            detail.run.createdAt | translocoDate: {dateStyle: "short", timeStyle: "medium"}
                        }}</span>
                    </div>
                    <div>
                        <span class="label">{{ "tasks.runs.tokens" | transloco }}</span>
                        <span>
                            {{ allTokens(detail.run) | translocoDecimal }}
                            @if (detail.run.cacheReadTokens > 0) {
                                ({{
                                    "tasks.runs.cached"
                                        | transloco: {count: detail.run.cacheReadTokens | translocoDecimal}
                                }})
                            }
                        </span>
                    </div>
                    @if (detail.run.cost !== null) {
                        <div [title]="'tasks.runs.costNote' | transloco">
                            <span class="label">{{ "tasks.runs.cost" | transloco }}</span>
                            <span>
                                @if (detail.run.costPartial) {
                                    {{ "tasks.runs.atLeast" | transloco }}
                                }
                                {{ detail.run.cost | translocoCurrency: "symbol" : costDigits : "EUR" }}
                            </span>
                        </div>
                    }
                </div>
                @if (open()) {
                    <mat-progress-bar mode="indeterminate" />
                }
                @if (detail.run.errorMessage) {
                    <p class="app-note error">
                        <mat-icon>error_outline</mat-icon>
                        <span>{{
                            errorOf(detail.run.errorCode, detail.run.errorMessage, failedStepParams(detail.steps))
                        }}</span>
                    </p>
                }
                @if (detail.run.mailFailed) {
                    <p class="app-note">
                        <mat-icon>mail</mat-icon><span>{{ "tasks.run.mailFailed" | transloco }}</span>
                    </p>
                }

                <div class="work">
                    <div class="app-panel">
                        <div class="app-panel-head">
                            <span class="app-panel-title">{{ "tasks.editor.graph" | transloco }}</span>
                        </div>
                        <div class="app-panel-body">
                            <app-task-graph
                                [steps]="detail.definition"
                                [states]="states()"
                                [selected]="selected()"
                                (stepChosen)="selected.set($event)"
                            />
                        </div>
                    </div>
                    <div class="app-panel">
                        <div class="app-panel-head">
                            <span class="app-panel-title">{{
                                selected()
                                    ? ("tasks.run.step" | transloco: {step: selected()})
                                    : ("tasks.run.pick" | transloco)
                            }}</span>
                        </div>
                        <div class="app-panel-body">
                            @for (step of selectedRounds(); track step.round) {
                                <div class="round">
                                    <h3>
                                        {{ "tasks.run.round" | transloco: {round: step.round} }} ·
                                        {{ "tasks.stepState." + step.state | transloco }}
                                        @if (allTokens(step) > 0) {
                                            · {{ allTokens(step) | translocoDecimal }}
                                            {{ "tasks.runs.tokens" | transloco }}
                                        }
                                        @if (step.cost !== null) {
                                            · {{ step.cost | translocoCurrency: "symbol" : costDigits : "EUR" }}
                                        }
                                    </h3>
                                    @if (step.errorCode) {
                                        <p class="error">
                                            {{ errorOf(step.errorCode, step.errorCode, errorParams(step)) }}
                                        </p>
                                    }
                                    @if (step.result; as result) {
                                        @if (result["documents"] !== undefined) {
                                            <p>
                                                {{ "tasks.run.documents" | transloco: {files: result["files"]} }}
                                                @if (result["truncated"]) {
                                                    {{ "tasks.run.documentsLeft" | transloco }}
                                                }
                                            </p>
                                            @for (document of documents(step); track document.name) {
                                                <div class="document">
                                                    <code>{{ document.name }}</code>
                                                    @if (document.problem) {
                                                        <span class="app-chip err">{{
                                                            "tasks.run.unreadable" | transloco
                                                        }}</span>
                                                        <span class="muted">{{ document.problem }}</span>
                                                    }
                                                </div>
                                            }
                                        } @else if (result["files"] !== undefined) {
                                            <p>
                                                {{
                                                    "tasks.run.read"
                                                        | transloco: {files: result["files"], lines: result["lines"]}
                                                }}
                                                @if (result["truncated"]) {
                                                    {{ "tasks.run.truncated" | transloco }}
                                                }
                                            </p>
                                        }
                                        @for (check of checks(step); track check.name) {
                                            <div class="check">
                                                <div>
                                                    <b>{{ check.name }}</b>
                                                    <span class="app-chip" [class]="checkChip(check).chip">{{
                                                        checkChip(check).label | transloco
                                                    }}</span>
                                                </div>
                                                <p>{{ check.note }}</p>
                                                @for (line of check.evidence; track $index) {
                                                    <code>{{ line.file }} {{ line.line }}: {{ line.text }}</code>
                                                }
                                            </div>
                                        }
                                        @if (answerOf(step); as answer) {
                                            @if (answer.choice) {
                                                <p>
                                                    {{ "tasks.run.choice" | transloco }}
                                                    <span class="app-chip info">{{ answer.choice }}</span>
                                                </p>
                                            }
                                            <div class="answer">{{ answer.text }}</div>
                                        }
                                        @if (result["rowIds"] !== undefined) {
                                            <p>
                                                {{
                                                    "tasks.run.rows"
                                                        | transloco: {rows: result["rows"], unclear: result["unclear"]}
                                                }}
                                            </p>
                                        }
                                        @if (result["written"] !== undefined) {
                                            <p>
                                                {{
                                                    "tasks.run.written"
                                                        | transloco: {rows: result["rows"], skipped: result["skipped"]}
                                                }}
                                            </p>
                                            @for (file of writtenFiles(step); track file) {
                                                <code>{{ file }}</code>
                                            }
                                        }
                                        @if (result["calls"] !== undefined) {
                                            <p>
                                                {{
                                                    "tasks.run.calls"
                                                        | transloco: {sent: result["sent"], failed: result["failed"]}
                                                }}
                                            </p>
                                            @for (call of calls(step); track $index) {
                                                <div class="document">
                                                    <code>{{ call.source }}</code>
                                                    <span
                                                        class="app-chip"
                                                        [class.ok]="!call.failed"
                                                        [class.err]="call.failed"
                                                        >{{
                                                            (call.failed
                                                                ? "tasks.run.callFailed"
                                                                : "tasks.run.callSent"
                                                            ) | transloco
                                                        }}</span
                                                    >
                                                    <span class="muted">{{ call.answer }}</span>
                                                </div>
                                            }
                                        }
                                        @if (result["sent"] !== undefined && result["calls"] === undefined) {
                                            <p>{{ "tasks.run.sent" | transloco: {count: result["sent"]} }}</p>
                                        }
                                    }
                                </div>
                            } @empty {
                                @if (selected()) {
                                    <p class="muted">{{ "tasks.run.noStep" | transloco }}</p>
                                }
                            }
                            @if (selectedRows().length > 0) {
                                @let columns = rowColumns(selectedRows());
                                <div class="rows">
                                    <table>
                                        <thead>
                                            <tr>
                                                <th>{{ "tasks.run.source" | transloco }}</th>
                                                @for (column of columns; track column) {
                                                    <th>{{ column }}</th>
                                                }
                                                <th>{{ "tasks.run.unclear" | transloco }}</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            @for (row of selectedRows(); track $index) {
                                                <tr [class.unclear]="row.unclear !== null">
                                                    <td class="cell-main">{{ row.source }}</td>
                                                    @for (column of columns; track column) {
                                                        <td>{{ row.values[column] ?? "" }}</td>
                                                    }
                                                    <td>
                                                        @if (row.unclear) {
                                                            <span class="app-chip warn">{{ row.unclear }}</span>
                                                        }
                                                    </td>
                                                </tr>
                                            }
                                        </tbody>
                                    </table>
                                </div>
                            }
                        </div>
                    </div>
                </div>
            } @else {
                <mat-progress-bar mode="indeterminate" />
            }
        </div>
    `,
    styles: [
        `
            .app-summary {
                display: flex;
                flex-wrap: wrap;
                gap: 24px;
                margin-bottom: 16px;
            }

            .app-summary > div {
                display: flex;
                flex-direction: column;
                gap: 4px;
            }

            .label {
                font-size: 12px;
                color: var(--app-text-2);
            }

            .work {
                display: grid;
                grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
                gap: 16px;
            }

            @media (max-width: 1100px) {
                .work {
                    grid-template-columns: minmax(0, 1fr);
                }
            }

            h3 {
                font-size: 13px;
                margin: 0 0 6px;
                color: var(--app-text-2);
            }

            .round + .round {
                margin-top: 16px;
                padding-top: 12px;
                border-top: 1px solid var(--app-border);
            }

            .check {
                margin: 10px 0;
            }

            .check p {
                margin: 4px 0;
            }

            .check .app-chip {
                margin-left: 8px;
            }

            code {
                display: block;
                font-family: var(--app-mono);
                font-size: 12px;
                background: var(--app-surface-2);
                padding: 4px 6px;
                border-radius: 4px;
                margin-top: 4px;
                overflow-wrap: anywhere;
            }

            .error {
                color: var(--app-err);
            }

            .answer {
                white-space: pre-wrap;
                overflow-wrap: anywhere;
                font-size: 13px;
                background: var(--app-surface-2);
                border-radius: 6px;
                padding: 8px 10px;
            }

            .muted {
                color: var(--app-text-2);
            }

            .document {
                display: flex;
                flex-wrap: wrap;
                align-items: center;
                gap: 8px;
                margin: 4px 0;
            }

            .document code {
                display: inline;
                margin: 0;
            }

            .rows {
                overflow-x: auto;
                margin-top: 12px;
            }

            table {
                border-collapse: collapse;
                font-size: 12px;
                width: 100%;
            }

            th,
            td {
                text-align: left;
                padding: 4px 8px;
                border-bottom: 1px solid var(--app-border);
                white-space: nowrap;
            }

            th {
                color: var(--app-text-2);
                font-weight: 500;
            }
        `,
    ],
})
export class TaskRunComponent implements OnInit, OnDestroy {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);

    readonly taskId = input.required<string>();
    readonly runId = input.required<string>();
    /** The tenant's id in the admin's address; none: the tenant admin's own tenant. */
    readonly id = input<string | undefined>(undefined);

    readonly scope = computed<TenantScope>(() => (this.id() ? Number(this.id()) : "self"));
    readonly taskLink = computed(() =>
        this.id() ? `/dashboard/tenants/${this.id()}/tasks/${this.taskId()}` : `/app/tasks/${this.taskId()}`,
    );
    readonly detail = this.store.selectSignal(TaskSelectors.selectRun);
    readonly selected = signal<string | null>(null);
    readonly open = computed(() => {
        const detail = this.detail();
        return detail !== null && isOpen(detail.run.state);
    });
    readonly states = computed(() => nodeStates(this.detail()?.steps ?? []));
    readonly selectedRounds = computed(() =>
        (this.detail()?.steps ?? []).filter(step => step.stepId === this.selected()),
    );
    /** The rows the chosen extract step gave - a run from before rows existed has none. */
    readonly selectedRows = computed(() => (this.detail()?.rows ?? []).filter(row => row.stepId === this.selected()));
    readonly rowColumns = rowColumns;
    readonly subtitle = computed(() => {
        const detail = this.detail();
        return detail ? {version: detail.run.taskVersion} : null;
    });
    readonly chipOf = runChip;
    readonly costDigits = COST_DIGITS;
    readonly allTokens = allTokens;

    constructor() {
        this.actions$.pipe(ofType(TaskActions.cancelRunSuccess), takeUntilDestroyed()).subscribe(() => this.watch());
        this.actions$
            .pipe(ofType(TaskActions.cancelRunFailure, TaskActions.loadRunFailure), takeUntilDestroyed())
            .subscribe(({error}) =>
                this.snackBar.open(
                    this.transloco.translate("tasks.snack.error", {error}),
                    this.transloco.translate("common.ok"),
                    {
                        duration: 10000,
                        panelClass: "error-snackbar",
                    },
                ),
            );
    }

    ngOnInit(): void {
        this.watch();
    }

    ngOnDestroy(): void {
        this.store.dispatch(TaskActions.watchRunStop());
    }

    cancel(): void {
        this.store.dispatch(
            TaskActions.cancelRun({scope: this.scope(), taskId: Number(this.taskId()), runId: Number(this.runId())}),
        );
    }

    /** A prompt step's answer in this round. */
    answerOf(step: TaskRunStep): TaskAnswer | null {
        return (
            this.detail()?.answers.find(answer => answer.stepId === step.stepId && answer.round === step.round) ?? null
        );
    }

    writtenFiles(step: TaskRunStep): string[] {
        const files = step.result?.["written"];
        return Array.isArray(files) ? (files as string[]) : [];
    }

    calls(step: TaskRunStep): TaskToolCall[] {
        return toolCalls(step);
    }

    documents(step: TaskRunStep): TaskRunDocument[] {
        return runDocuments(step);
    }

    checks(step: TaskRunStep): TaskCheckResult[] {
        const checks = step.result?.["checks"];
        return Array.isArray(checks) ? (checks as TaskCheckResult[]) : [];
    }

    checkChip(check: TaskCheckResult): {chip: string; label: string} {
        return checkChip(check);
    }

    errorParams(step: TaskRunStep): Record<string, string> {
        return errorParams(step);
    }

    failedStepParams(steps: TaskRunStep[]): Record<string, string> {
        return failedStepParams(steps);
    }

    /** The backend's code translated - its message where the frontend has no text for it. */
    errorOf(code: string | null, message: string, params: Record<string, string>): string {
        if (!code) {
            return message;
        }
        const key = "errors.backend." + code;
        const translated = this.transloco.translate(key, params);
        return translated === key ? message : translated;
    }

    private watch(): void {
        this.store.dispatch(
            TaskActions.watchRun({scope: this.scope(), taskId: Number(this.taskId()), runId: Number(this.runId())}),
        );
    }
}
