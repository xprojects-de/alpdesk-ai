import {Component, DestroyRef, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {Router, RouterLink} from "@angular/router";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
import {filter} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog} from "@angular/material/dialog";
import {MatIconModule} from "@angular/material/icon";
import {MatMenuModule} from "@angular/material/menu";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTableModule} from "@angular/material/table";
import {TaskSummary} from "../../../models/task.model";
import {TenantScope} from "../../../models/tenant.model";
import * as TaskActions from "../../../store/task/task.actions";
import * as TaskSelectors from "../../../store/task/task.selectors";
import {DRAFT_NAVIGATION, runChip, triggerTexts} from "../../../utils/task.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {EmptyStateComponent} from "../empty-state/empty-state.component";

/**
 * The scheduled tasks of one tenant (TenantScope) as a table: when each runs, its last run, its next one. A row opens
 * the editor; a new task starts from a template there, or from a file imported here.
 */
@Component({
    selector: "app-tasks-panel",
    imports: [
        RouterLink,
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatProgressBarModule,
        MatTableModule,
        EmptyStateComponent,
        TranslocoPipe,
        TranslocoDatePipe,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "tasks.panel.title" | transloco }}</span>
                <span class="muted">{{ "tasks.panel.intro" | transloco }}</span>
                <span class="spacer"></span>
                <input #file type="file" accept=".yaml,.yml,.json,.txt" hidden (change)="importFile(file)" />
                <button mat-stroked-button (click)="file.click()">
                    <mat-icon>upload_file</mat-icon>
                    {{ "tasks.panel.import" | transloco }}
                </button>
                <a mat-stroked-button [routerLink]="linkBase() + '/new'" [queryParams]="{draft: 1}">
                    <mat-icon>auto_awesome</mat-icon>
                    {{ "tasks.panel.addWithAi" | transloco }}
                </a>
                <a mat-flat-button [routerLink]="linkBase() + '/new'">
                    <mat-icon>add</mat-icon>
                    {{ "tasks.panel.add" | transloco }}
                </a>
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (tasks().length === 0) {
                @if (!loading()) {
                    <app-empty-state
                        icon="schedule"
                        [title]="'tasks.panel.emptyTitle' | transloco"
                        [text]="'tasks.panel.emptyText' | transloco"
                    >
                        <a mat-stroked-button [routerLink]="linkBase() + '/new'" [queryParams]="{draft: 1}">
                            <mat-icon>auto_awesome</mat-icon>
                            {{ "tasks.panel.addWithAi" | transloco }}
                        </a>
                        <a mat-flat-button [routerLink]="linkBase() + '/new'">
                            <mat-icon>add</mat-icon>
                            {{ "tasks.panel.add" | transloco }}
                        </a>
                    </app-empty-state>
                }
            } @else {
                <table mat-table [dataSource]="tasks()" [trackBy]="trackById">
                    <ng-container matColumnDef="name">
                        <th mat-header-cell *matHeaderCellDef>{{ "tasks.panel.name" | transloco }}</th>
                        <td mat-cell *matCellDef="let task">
                            <span class="cell-main">{{ task.name }}</span>
                            @if (!task.active) {
                                <span class="app-chip">{{ "tasks.panel.off" | transloco }}</span>
                            }
                        </td>
                    </ng-container>
                    <ng-container matColumnDef="triggers">
                        <th mat-header-cell *matHeaderCellDef>{{ "tasks.panel.triggers" | transloco }}</th>
                        <td mat-cell *matCellDef="let task">
                            @for (trigger of triggers(task); track $index) {
                                <div>{{ trigger.key | transloco: trigger.params }}</div>
                            }
                        </td>
                    </ng-container>
                    <ng-container matColumnDef="lastRun">
                        <th mat-header-cell *matHeaderCellDef>{{ "tasks.panel.lastRun" | transloco }}</th>
                        <td mat-cell *matCellDef="let task">
                            @if (task.lastRun; as run) {
                                @let chip = chipOf(run.state);
                                <span class="app-chip" [class]="chip.chip">{{ chip.label | transloco }}</span>
                                <span class="muted">{{
                                    run.createdAt | translocoDate: {dateStyle: "short", timeStyle: "short"}
                                }}</span>
                            } @else {
                                <span class="muted">{{ "tasks.panel.never" | transloco }}</span>
                            }
                        </td>
                    </ng-container>
                    <ng-container matColumnDef="nextRun">
                        <th mat-header-cell *matHeaderCellDef>{{ "tasks.panel.nextRun" | transloco }}</th>
                        <td mat-cell *matCellDef="let task">
                            @if (task.nextRunAt) {
                                {{ task.nextRunAt | translocoDate: {dateStyle: "short", timeStyle: "short"} }}
                            } @else {
                                <span class="muted">-</span>
                            }
                        </td>
                    </ng-container>
                    <ng-container matColumnDef="actions">
                        <th mat-header-cell *matHeaderCellDef></th>
                        <td mat-cell *matCellDef="let task" (click)="$event.stopPropagation()">
                            <button
                                mat-icon-button
                                [matMenuTriggerFor]="menu"
                                [attr.aria-label]="'common.more' | transloco"
                            >
                                <mat-icon>more_vert</mat-icon>
                            </button>
                            <mat-menu #menu="matMenu">
                                <button mat-menu-item (click)="start(task)" [disabled]="!task.active">
                                    <mat-icon>play_arrow</mat-icon>
                                    {{ "tasks.panel.start" | transloco }}
                                </button>
                                <button mat-menu-item class="danger" (click)="confirmDelete(task)">
                                    <mat-icon>delete</mat-icon>
                                    {{ "common.delete" | transloco }}
                                </button>
                            </mat-menu>
                        </td>
                    </ng-container>
                    <tr mat-header-row *matHeaderRowDef="columns"></tr>
                    <tr mat-row *matRowDef="let task; columns: columns" class="row" (click)="open(task)"></tr>
                </table>
            }
        </div>
    `,
    styles: [
        `
            table {
                width: 100%;
            }

            .row {
                cursor: pointer;
            }

            .row:hover {
                background: var(--app-surface-2);
            }

            td .app-chip,
            td .app-tag {
                margin-left: 8px;
            }

            .muted {
                color: var(--app-text-2);
                margin-left: 6px;
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class TasksPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);

    readonly scope = input.required<TenantScope>();
    /** Where the editor of a task lives: /app/tasks, /dashboard/tenants/<id>/tasks. */
    readonly linkBase = input.required<string>();

    readonly tasks = this.store.selectSignal(TaskSelectors.selectTasks);
    readonly loading = this.store.selectSignal(TaskSelectors.selectTasksLoading);
    readonly columns = ["name", "triggers", "lastRun", "nextRun", "actions"];
    readonly triggers = (task: TaskSummary) => triggerTexts(task.triggers);
    readonly chipOf = runChip;
    readonly trackById = (_: number, task: TaskSummary) => task.id;

    constructor() {
        this.actions$
            .pipe(ofType(TaskActions.startTaskSuccess), takeUntilDestroyed())
            .subscribe(() => this.notify(this.transloco.translate("tasks.snack.started")));
        this.actions$
            .pipe(ofType(TaskActions.deleteTaskSuccess), takeUntilDestroyed())
            .subscribe(() => this.notify(this.transloco.translate("tasks.snack.deleted")));
        this.actions$
            .pipe(ofType(TaskActions.startTaskFailure, TaskActions.deleteTaskFailure), takeUntilDestroyed())
            .subscribe(({error}) => this.notify(this.transloco.translate("tasks.snack.error", {error}), true));
    }

    ngOnInit(): void {
        this.store.dispatch(TaskActions.loadTasks({scope: this.scope()}));
    }

    open(task: TaskSummary): void {
        void this.router.navigate([this.linkBase(), task.id]);
    }

    start(task: TaskSummary): void {
        this.store.dispatch(TaskActions.startTask({scope: this.scope(), taskId: task.id}));
    }

    /** A file's text opens as a new task in the editor - nothing is saved before the editor saves it. */
    importFile(input: HTMLInputElement): void {
        const file = input.files?.[0];
        input.value = "";
        if (!file) {
            return;
        }
        void file.text().then(source => {
            this.store.dispatch(TaskActions.newTask({scope: this.scope(), source}));
            void this.router.navigate([this.linkBase(), "new"], {state: DRAFT_NAVIGATION});
        });
    }

    confirmDelete(task: TaskSummary): void {
        const data: ConfirmDialogData = {
            message: this.transloco.translate("tasks.confirmDelete", {name: task.name}),
            confirmLabel: this.transloco.translate("common.delete"),
            confirmColor: "warn",
        };
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {width: "480px", data})
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(TaskActions.deleteTask({scope: this.scope(), taskId: task.id})));
    }

    private notify(message: string, isError = false): void {
        this.snackBar.open(message, this.transloco.translate("common.ok"), {
            duration: isError ? 10000 : 4000,
            panelClass: isError ? "error-snackbar" : undefined,
        });
    }
}
