import {Component, computed, DestroyRef, effect, inject, input, OnInit, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {Router, RouterLink} from "@angular/router";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoCurrencyPipe, TranslocoDatePipe, TranslocoDecimalPipe} from "@jsverse/transloco-locale";
import {Observable, of} from "rxjs";
import {filter, map} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog} from "@angular/material/dialog";
import {MatIconModule} from "@angular/material/icon";
import {MatMenuModule} from "@angular/material/menu";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TaskDraftRequest, TaskError, TaskRunSummary, TaskStepView} from "../../models/task.model";
import {TenantScope} from "../../models/tenant.model";
import * as FileStoreActions from "../../store/file-store/file-store.actions";
import * as FileStoreSelectors from "../../store/file-store/file-store.selectors";
import * as TaskActions from "../../store/task/task.actions";
import * as TaskSelectors from "../../store/task/task.selectors";
import {saveBlob} from "../../utils/download.util";
import {
    allTokens,
    changedLines,
    COST_DIGITS,
    DRAFT_NAVIGATION,
    errorText,
    isOpen,
    runChip,
    startsWithDraft,
    stepAt,
    stepRanges,
    taskTemplate,
} from "../../utils/task.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {TaskGraphComponent} from "../shared/task-graph/task-graph.component";
import {TaskSourceEditorComponent} from "../shared/task-source-editor/task-source-editor.component";
import {TaskReferenceDialogComponent} from "./task-reference-dialog.component";
import {TaskDraftDialogComponent, TaskDraftDialogData} from "./task-draft-dialog.component";
import {DialogSave} from "../shared/dialog-save";

/**
 * The editor of a task (docs/architecture/tasks.md "The editor"): the text on the left, the graph on the right - drawn
 * when the text is checked ("Prüfen", before every save, when the task is opened), not while typing. A mistake stands
 * at its line and red at its step; a click on a step marks its lines. Below: the task's runs.
 */
@Component({
    selector: "app-task-editor",
    imports: [
        RouterLink,
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatProgressBarModule,
        MatSlideToggleModule,
        PageHeaderComponent,
        TaskGraphComponent,
        TaskSourceEditorComponent,
        TranslocoPipe,
        TranslocoDatePipe,
        TranslocoDecimalPipe,
        TranslocoCurrencyPipe,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="current()?.name ?? ('tasks.editor.newTitle' | transloco)"
                [subtitle]="subtitle().key | transloco: subtitle().params"
                [backLink]="linkBase()"
                [backLabel]="'tasks.editor.back' | transloco"
            >
                <button mat-stroked-button (click)="openDraft()">
                    <mat-icon>auto_awesome</mat-icon>
                    {{ "tasks.editor.draft" | transloco }}
                </button>
                <button mat-button (click)="openReference()">
                    <mat-icon>help_outline</mat-icon>
                    {{ "tasks.editor.reference" | transloco }}
                </button>
                <button mat-stroked-button (click)="check()" [disabled]="validating()">
                    <mat-icon>rule</mat-icon>
                    {{ "tasks.editor.check" | transloco }}
                </button>
                @if (current(); as task) {
                    <button mat-stroked-button (click)="start()" [disabled]="dirty() || !task.active">
                        <mat-icon>play_arrow</mat-icon>
                        {{ "tasks.editor.start" | transloco }}
                    </button>
                    <button mat-icon-button [matMenuTriggerFor]="more" [attr.aria-label]="'common.more' | transloco">
                        <mat-icon>more_vert</mat-icon>
                    </button>
                    <mat-menu #more="matMenu">
                        <button mat-menu-item (click)="export()">
                            <mat-icon>download</mat-icon>
                            {{ "tasks.editor.export" | transloco }}
                        </button>
                        <button mat-menu-item (click)="duplicate()">
                            <mat-icon>content_copy</mat-icon>
                            {{ "tasks.editor.duplicate" | transloco }}
                        </button>
                        <button mat-menu-item class="danger" (click)="confirmDelete()">
                            <mat-icon>delete</mat-icon>
                            {{ "common.delete" | transloco }}
                        </button>
                    </mat-menu>
                }
                <button mat-flat-button (click)="save()" [disabled]="saving()">
                    <mat-icon>save</mat-icon>
                    {{ "common.save" | transloco }}
                </button>
            </app-page-header>

            @if (currentLoading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            <div class="switches">
                <mat-slide-toggle [checked]="active()" (change)="active.set($event.checked); touched.set(true)">
                    {{ "tasks.editor.active" | transloco }}
                </mat-slide-toggle>
                @if (savedTask(); as task) {
                    <span class="keys muted" [title]="'tasks.editor.keysHint' | transloco">
                        @if (task.keys.length === 0) {
                            {{ "tasks.editor.noKeys" | transloco }}
                        } @else {
                            {{ "tasks.editor.keys" | transloco }}
                            @for (key of task.keys; track $index) {
                                <span class="app-tag">{{
                                    (key.kind === "API" ? "tasks.editor.keyApi" : "tasks.editor.keyMcp")
                                        | transloco: {name: key.name}
                                }}</span>
                            }
                        }
                    </span>
                }
            </div>

            <div class="work">
                <div class="app-panel text">
                    <div class="app-panel-head">
                        <span class="app-panel-title">{{ "tasks.editor.text" | transloco }}</span>
                        @if (dirty()) {
                            <span class="app-chip warn">{{ "tasks.editor.unsaved" | transloco }}</span>
                        }
                    </div>
                    <div class="app-panel-body">
                        @if (shown() !== null) {
                            <app-task-source-editor
                                [source]="shown()!"
                                [errorLines]="errorLines()"
                                [changedLines]="changed()"
                                [range]="selectedRange()"
                                [label]="'tasks.editor.text' | transloco"
                                (changed)="text.set($event)"
                                (lineChosen)="chooseLine($event)"
                            />
                        }
                        @if (errors().length > 0) {
                            <ul class="errors" role="alert">
                                @for (error of errors(); track $index) {
                                    <li>
                                        <mat-icon>error_outline</mat-icon>
                                        @if (error.line > 0) {
                                            <b>{{ "tasks.editor.line" | transloco: {line: error.line} }}</b>
                                        }
                                        {{ message(error) }}
                                    </li>
                                }
                            </ul>
                        } @else if (validation()?.valid) {
                            <p class="ok"><mat-icon>check_circle</mat-icon>{{ "tasks.editor.valid" | transloco }}</p>
                        }
                    </div>
                </div>
                <div class="app-panel graph">
                    <div class="app-panel-head">
                        <span class="app-panel-title">{{ "tasks.editor.graph" | transloco }}</span>
                        @if (validating()) {
                            <span class="muted">{{ "tasks.editor.checking" | transloco }}</span>
                        }
                    </div>
                    <div class="app-panel-body">
                        @if (steps().length > 0) {
                            <app-task-graph
                                [steps]="steps()"
                                [errorSteps]="errorSteps()"
                                [selected]="selectedStep()"
                                (stepChosen)="selectedStep.set($event)"
                            />
                        } @else {
                            <p class="muted">{{ "tasks.editor.noGraph" | transloco }}</p>
                        }
                    </div>
                </div>
            </div>

            @if (current(); as task) {
                <div class="app-panel">
                    <div class="app-panel-head">
                        <span class="app-panel-title">{{ "tasks.runs.title" | transloco }}</span>
                        <span class="spacer"></span>
                        @if (runs().length > 0) {
                            <button mat-button class="danger" (click)="confirmDeleteRuns()">
                                <mat-icon>delete_sweep</mat-icon>
                                {{ "tasks.runs.deleteAll" | transloco }}
                            </button>
                        }
                    </div>
                    @if (runs().length === 0) {
                        <p class="app-panel-body muted">{{ "tasks.runs.none" | transloco }}</p>
                    } @else {
                        <table class="runs">
                            <thead>
                                <tr>
                                    <th>{{ "tasks.runs.started" | transloco }}</th>
                                    <th>{{ "tasks.runs.by" | transloco }}</th>
                                    <th>{{ "tasks.runs.state" | transloco }}</th>
                                    <th>{{ "tasks.runs.tokens" | transloco }}</th>
                                    <th [title]="'tasks.runs.costNote' | transloco">
                                        {{ "tasks.runs.cost" | transloco }}
                                    </th>
                                    <th></th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (run of runs(); track run.id) {
                                    @let chip = chipOf(run.state);
                                    <tr>
                                        <td>
                                            <a [routerLink]="[linkBase(), task.id, 'runs', run.id]">{{
                                                run.createdAt | translocoDate: {dateStyle: "short", timeStyle: "medium"}
                                            }}</a>
                                        </td>
                                        <td>{{ "tasks.startedBy." + run.startedBy | transloco }}</td>
                                        <td>
                                            <span class="app-chip" [class]="chip.chip">{{
                                                chip.label | transloco
                                            }}</span>
                                            @if (run.mailFailed) {
                                                <span class="app-chip err">{{
                                                    "tasks.runs.mailFailed" | transloco
                                                }}</span>
                                            }
                                        </td>
                                        <td>{{ allTokens(run) | translocoDecimal }}</td>
                                        <td>
                                            @if (run.cost !== null) {
                                                @if (run.costPartial) {
                                                    {{ "tasks.runs.atLeast" | transloco }}
                                                }
                                                {{ run.cost | translocoCurrency: "symbol" : costDigits : "EUR" }}
                                            }
                                        </td>
                                        <td class="actions">
                                            @if (open(run)) {
                                                <button
                                                    mat-button
                                                    (click)="cancel(run)"
                                                    [disabled]="run.cancelRequested"
                                                >
                                                    <mat-icon>stop</mat-icon>
                                                    {{ "tasks.runs.cancel" | transloco }}
                                                </button>
                                            } @else {
                                                <button
                                                    mat-icon-button
                                                    (click)="deleteRun(run)"
                                                    [attr.aria-label]="'common.delete' | transloco"
                                                >
                                                    <mat-icon>delete</mat-icon>
                                                </button>
                                            }
                                        </td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                    }
                </div>
            }
        </div>
    `,
    styles: [
        `
            .switches {
                display: flex;
                gap: 24px;
                flex-wrap: wrap;
                align-items: center;
                margin: 0 0 16px;
            }

            .keys {
                display: flex;
                gap: 6px;
                flex-wrap: wrap;
                align-items: center;
            }

            .work {
                display: grid;
                grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
                gap: 16px;
                margin-bottom: 16px;
            }

            @media (max-width: 1100px) {
                .work {
                    grid-template-columns: minmax(0, 1fr);
                }
            }

            .errors {
                list-style: none;
                margin: 12px 0 0;
                padding: 0;
                display: flex;
                flex-direction: column;
                gap: 6px;
                color: var(--app-err);
                font-size: 13px;
            }

            .errors li,
            .ok {
                display: flex;
                gap: 6px;
                align-items: flex-start;
            }

            .ok {
                color: var(--app-ok);
                margin: 12px 0 0;
            }

            mat-icon {
                flex: none;
            }

            .muted {
                color: var(--app-text-2);
            }

            .runs {
                width: 100%;
                border-collapse: collapse;
                font-size: 13px;
            }

            .runs th {
                text-align: left;
                color: var(--app-text-2);
                font-weight: 600;
                padding: 8px 14px;
            }

            .runs td {
                padding: 6px 14px;
                border-top: 1px solid var(--app-border);
            }

            .runs .actions {
                text-align: right;
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class TaskEditorComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);

    /** From the address: "new" or the task's id. */
    readonly taskId = input.required<string>();
    /** The tenant's id in the admin's address; none: the tenant admin's own tenant. */
    readonly id = input<string | undefined>(undefined);

    readonly scope = computed<TenantScope>(() => (this.id() ? Number(this.id()) : "self"));
    readonly linkBase = computed(() => (this.id() ? `/dashboard/tenants/${this.id()}/tasks` : "/app/tasks"));

    readonly current = this.store.selectSignal(TaskSelectors.selectCurrent);
    readonly currentLoading = this.store.selectSignal(TaskSelectors.selectCurrentLoading);
    /** The task as saved, for the keys that may start it - chosen at the keys, not here. */
    readonly savedTask = computed(() => {
        const task = this.current();
        return task && this.taskId() === String(task.id) ? task : null;
    });
    private readonly startText = this.store.selectSignal(TaskSelectors.selectDraft);
    readonly validation = this.store.selectSignal(TaskSelectors.selectValidation);
    readonly validating = this.store.selectSignal(TaskSelectors.selectValidating);
    readonly saving = this.store.selectSignal(TaskSelectors.selectSaving);
    readonly runs = this.store.selectSignal(TaskSelectors.selectRuns);
    private readonly stores = this.store.selectSignal(FileStoreSelectors.selectStores);
    private readonly storesLoading = this.store.selectSignal(FileStoreSelectors.selectStoresLoading);

    /** "1" in the address (?draft=1): the draft dialog opens with the new task. */
    readonly draft = input<string | undefined>(undefined);

    /** The text as saved (or the new task's start); the text the editor shows; the text as edited. */
    readonly initial = signal<string | null>(null);
    readonly shown = signal<string | null>(null);
    /** The lines the last draft wrote new. */
    readonly changed = signal<number[]>([]);
    readonly text = signal("");
    readonly active = signal(true);
    readonly touched = signal(false);
    readonly selectedStep = signal<string | null>(null);
    /** The steps of the last text that compiled - the graph stays while the text has a mistake. */
    readonly steps = signal<TaskStepView[]>([]);

    readonly dirty = computed(() => this.touched() || (this.initial() !== null && this.text() !== this.initial()));
    readonly errors = computed(() => this.validation()?.errors ?? []);
    readonly errorLines = computed(() =>
        this.errors()
            .map(error => error.line)
            .filter(line => line > 0),
    );
    private readonly ranges = computed(() => stepRanges(this.steps(), this.text().split("\n").length));
    readonly selectedRange = computed(() => {
        const step = this.selectedStep();
        return step ? (this.ranges().get(step) ?? null) : null;
    });
    readonly errorSteps = computed(() =>
        this.errorLines()
            .map(line => stepAt(line, this.ranges()))
            .filter((step): step is string => step !== null),
    );
    readonly subtitle = computed(() => {
        const task = this.current();
        return task
            ? {key: "tasks.editor.subtitle", params: {version: task.version}}
            : {key: "tasks.editor.newSubtitle", params: {}};
    });
    readonly chipOf = runChip;
    readonly costDigits = COST_DIGITS;
    readonly allTokens = allTokens;
    readonly open = (run: TaskRunSummary) => isOpen(run.state);
    private templateSet = false;

    constructor() {
        effect(() => {
            const steps = this.validation()?.steps ?? [];
            if (steps.length > 0) {
                this.steps.set(steps);
            }
        });
        effect(() => {
            const task = this.current();
            if (task && this.taskId() === String(task.id) && this.initial() === null) {
                this.show(task.source, task.active);
            }
        });
        // A new task: an imported text, else the template with the tenant's first file store once they are loaded.
        effect(() => {
            if (this.taskId() !== "new" || this.templateSet || this.storesLoading()) {
                return;
            }
            this.templateSet = true;
            const draft = this.startText();
            const source = draft || taskTemplate(this.stores()[0]?.name ?? null, null);
            this.show(source, true);
            // An imported or duplicated text is unsaved work: leaving asks, like after an edit.
            this.touched.set(draft !== null && draft !== "");
            this.store.dispatch(TaskActions.validateTask({scope: this.scope(), source}));
            if (this.draft() === "1") {
                this.openDraft();
            }
        });
        this.actions$.pipe(ofType(TaskActions.saveTaskSuccess), takeUntilDestroyed()).subscribe(({task}) => {
            if (this.text() === task.source) {
                this.show(task.source, task.active);
            } else {
                // Typed on while the save went out: showing the saved text would throw that away - the saved text
                // is what the editor compares with now, what came after it stays an unsaved change.
                this.initial.set(task.source);
                this.active.set(task.active);
                this.changed.set([]);
                this.touched.set(false);
            }
            this.notify(this.transloco.translate("tasks.snack.saved"));
            if (this.taskId() === "new") {
                void this.router.navigate([this.linkBase(), task.id], {replaceUrl: true});
            }
        });
        // A refused save shows every mistake at its line: the check lists them all.
        this.actions$.pipe(ofType(TaskActions.saveTaskFailure), takeUntilDestroyed()).subscribe(({error}) => {
            this.notify(this.transloco.translate("tasks.snack.error", {error}), true);
            this.check();
        });
        // A draft is an unsaved change of the text - its new lines marked, its graph drawn (it is checked already).
        this.actions$.pipe(ofType(TaskActions.draftTaskSuccess), takeUntilDestroyed()).subscribe(({draft}) => {
            const before = this.text();
            this.shown.set(draft.source);
            this.text.set(draft.source);
            this.changed.set(this.current() ? changedLines(before, draft.source) : []);
            this.notify(
                this.transloco.translate(draft.valid ? "tasks.snack.drafted" : "tasks.snack.draftedWithErrors"),
            );
        });
        this.actions$.pipe(ofType(TaskActions.startTaskSuccess), takeUntilDestroyed()).subscribe(({taskId, runId}) => {
            void this.router.navigate([this.linkBase(), taskId, "runs", runId]);
        });
        this.actions$.pipe(ofType(TaskActions.deleteTaskSuccess), takeUntilDestroyed()).subscribe(() => {
            this.touched.set(false);
            this.initial.set(this.text());
            void this.router.navigate([this.linkBase()]);
        });
        this.actions$
            .pipe(
                ofType(
                    TaskActions.startTaskFailure,
                    TaskActions.deleteTaskFailure,
                    TaskActions.cancelRunFailure,
                    TaskActions.deleteRunsFailure,
                    TaskActions.validateTaskFailure,
                    TaskActions.draftTaskFailure,
                    TaskActions.loadDraftModelsFailure,
                    TaskActions.loadReferenceFailure,
                ),
                takeUntilDestroyed(),
            )
            .subscribe(({error}) => this.notify(this.transloco.translate("tasks.snack.error", {error}), true));
    }

    ngOnInit(): void {
        if (this.taskId() === "new") {
            this.store.dispatch(FileStoreActions.loadStores({scope: this.scope()}));
            // "New task" starts from the template; only an import or a duplicate brings its text along (the
            // navigation says so - a draft left in the slice from an earlier visit must not come back).
            if (!startsWithDraft(history.state)) {
                this.store.dispatch(TaskActions.newTask({scope: this.scope(), source: ""}));
            }
            return;
        }
        this.store.dispatch(TaskActions.openTask({scope: this.scope(), taskId: Number(this.taskId())}));
    }

    check(): void {
        this.store.dispatch(TaskActions.validateTask({scope: this.scope(), source: this.text()}));
    }

    save(): void {
        const task = this.current();
        this.store.dispatch(
            TaskActions.saveTask({
                scope: this.scope(),
                taskId: this.taskId() === "new" || !task ? null : task.id,
                request: {source: this.text(), active: this.active()},
            }),
        );
    }

    start(): void {
        const task = this.current();
        if (task) {
            this.store.dispatch(TaskActions.startTask({scope: this.scope(), taskId: task.id}));
        }
    }

    chooseLine(line: number): void {
        const step = stepAt(line, this.ranges());
        if (step) {
            this.selectedStep.set(step);
        }
    }

    message(error: TaskError): string {
        const text = errorText(error);
        const translated = this.transloco.translate(text.key, text.params);
        return translated === text.key ? error.message : translated;
    }

    export(): void {
        const task = this.current();
        if (task) {
            saveBlob(new Blob([this.text()], {type: "text/yaml"}), `${task.name}.yaml`);
        }
    }

    /** The text as a new task - its name must change before it is saved, the editor shows that. */
    duplicate(): void {
        this.store.dispatch(TaskActions.newTask({scope: this.scope(), source: this.text()}));
        this.initial.set(this.text());
        this.touched.set(false);
        void this.router.navigate([this.linkBase(), "new"], {state: DRAFT_NAVIGATION});
    }

    openDraft(): void {
        this.dialogSave.open<TaskDraftDialogComponent, TaskDraftDialogData, TaskDraftRequest>(
            TaskDraftDialogComponent,
            {
                data: {scope: this.scope(), source: this.current() ? this.text() : null},
                width: "640px",
                maxWidth: "95vw",
            },
            {
                action: request => TaskActions.draftTask({scope: this.scope(), request}),
                success: [TaskActions.draftTaskSuccess],
                failure: TaskActions.draftTaskFailure,
            },
        );
    }

    openReference(): void {
        this.dialog.open(TaskReferenceDialogComponent, {
            width: "860px",
            maxWidth: "95vw",
            maxHeight: "86vh",
            data: this.scope(),
        });
    }

    cancel(run: TaskRunSummary): void {
        const task = this.current();
        if (task) {
            this.store.dispatch(TaskActions.cancelRun({scope: this.scope(), taskId: task.id, runId: run.id}));
        }
    }

    deleteRun(run: TaskRunSummary): void {
        const task = this.current();
        if (task) {
            this.store.dispatch(TaskActions.deleteRuns({scope: this.scope(), taskId: task.id, runId: run.id}));
        }
    }

    confirmDeleteRuns(): void {
        const task = this.current();
        if (!task) {
            return;
        }
        this.confirm({
            message: this.transloco.translate("tasks.runs.confirmDeleteAll", {name: task.name}),
            confirmLabel: this.transloco.translate("common.delete"),
            confirmColor: "warn",
        }).subscribe(() =>
            this.store.dispatch(TaskActions.deleteRuns({scope: this.scope(), taskId: task.id, runId: null})),
        );
    }

    confirmDelete(): void {
        const task = this.current();
        if (!task) {
            return;
        }
        this.confirm({
            message: this.transloco.translate("tasks.confirmDelete", {name: task.name}),
            confirmLabel: this.transloco.translate("common.delete"),
            confirmColor: "warn",
        }).subscribe(() => this.store.dispatch(TaskActions.deleteTask({scope: this.scope(), taskId: task.id})));
    }

    /** For the route's guard: leaving with unsaved changes is asked about. */
    mayLeave(): Observable<boolean> {
        if (!this.dirty()) {
            return of(true);
        }
        return this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "480px",
                data: {
                    message: this.transloco.translate("tasks.editor.leave"),
                    confirmLabel: this.transloco.translate("tasks.editor.leaveConfirm"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(map(Boolean));
    }

    private show(source: string, active: boolean): void {
        this.initial.set(source);
        this.shown.set(source);
        this.changed.set([]);
        this.text.set(source);
        this.active.set(active);
        this.touched.set(false);
    }

    private confirm(data: ConfirmDialogData) {
        return this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {width: "480px", data})
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef));
    }

    private notify(message: string, isError = false): void {
        this.snackBar.open(message, this.transloco.translate("common.ok"), {
            duration: isError ? 10000 : 4000,
            panelClass: isError ? "error-snackbar" : undefined,
        });
    }
}
