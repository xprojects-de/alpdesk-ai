import {Component, inject, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
import {Actions, ofType} from "@ngrx/effects";
import {Store} from "@ngrx/store";
import {Subject} from "rxjs";
import {debounceTime, distinctUntilChanged} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTableModule} from "@angular/material/table";
import {MatTooltipModule} from "@angular/material/tooltip";
import {ApplicationLogEntry, ApplicationLogFilter} from "../../models/application-log.model";
import * as ApplicationLogActions from "../../store/application-log/application-log.actions";
import * as ApplicationLogSelectors from "../../store/application-log/application-log.selectors";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";

/**
 * Admin page: the backend's recent warnings and errors, read from its in-memory buffer - what went wrong without SSH
 * to the server. A row opens to show the thread, the full logger name and the stack trace. The daily report is sent
 * from here too, to try the mail settings.
 */
@Component({
    selector: "app-application-log-list",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatProgressBarModule,
        MatTooltipModule,
        PageHeaderComponent,
        EmptyStateComponent,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'applicationLog.title' | transloco"
                [subtitle]="'applicationLog.subtitle' | transloco"
            >
                <button
                    mat-flat-button
                    (click)="sendDailyReport()"
                    [disabled]="sending()"
                    [matTooltip]="'applicationLog.dailyReport.tooltip' | transloco"
                >
                    <mat-icon>forward_to_inbox</mat-icon>
                    {{ "applicationLog.dailyReport.send" | transloco }}
                </button>
            </app-page-header>

            <div class="app-panel">
                <div class="app-panel-head">
                    <div class="app-filters">
                        <button
                            type="button"
                            class="app-filter"
                            [attr.aria-pressed]="filter().level === null"
                            (click)="chooseLevel(null)"
                        >
                            {{ "applicationLog.filter.all" | transloco }}
                        </button>
                        <button
                            type="button"
                            class="app-filter"
                            [attr.aria-pressed]="filter().level === 'ERROR'"
                            (click)="chooseLevel('ERROR')"
                        >
                            {{ "applicationLog.filter.errors" | transloco }}
                        </button>
                    </div>
                    <div class="app-search">
                        <mat-icon>search</mat-icon>
                        <input
                            type="search"
                            [placeholder]="'applicationLog.searchPlaceholder' | transloco"
                            [attr.aria-label]="'applicationLog.searchLabel' | transloco"
                            [value]="filter().search"
                            (input)="search($any($event.target).value)"
                        />
                    </div>
                    <span class="spacer"></span>
                    <button mat-icon-button (click)="reload()" [matTooltip]="'applicationLog.refresh' | transloco">
                        <mat-icon>refresh</mat-icon>
                    </button>
                </div>

                @if (startedAt(); as started) {
                    <div class="app-summary">
                        {{
                            "applicationLog.summary"
                                | transloco
                                    : {
                                          count: entries().length,
                                          capacity: capacity(),
                                          started: started | translocoDate: ({dateStyle: "medium", timeStyle: "short"}),
                                      }
                        }}
                    </div>
                }

                @if (loading()) {
                    <mat-progress-bar mode="indeterminate" />
                }

                @if (entries().length === 0 && !loading()) {
                    <app-empty-state
                        icon="monitor_heart"
                        [title]="'applicationLog.empty.title' | transloco"
                        [text]="'applicationLog.empty.text' | transloco"
                    />
                } @else {
                    <div class="table-container">
                        <table mat-table [dataSource]="entries()" multiTemplateDataRows class="log-table">
                            <ng-container matColumnDef="timestamp">
                                <th mat-header-cell *matHeaderCellDef>
                                    {{ "applicationLog.column.time" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let entry" class="num muted nowrap">
                                    {{ entry.timestamp | translocoDate: {dateStyle: "short", timeStyle: "medium"} }}
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="level">
                                <th mat-header-cell *matHeaderCellDef>
                                    {{ "applicationLog.column.level" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let entry">
                                    @if (entry.level === "ERROR") {
                                        <span class="app-chip err">{{ "applicationLog.level.error" | transloco }}</span>
                                    } @else {
                                        <span class="app-chip warn">{{
                                            "applicationLog.level.warning" | transloco
                                        }}</span>
                                    }
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="logger">
                                <th mat-header-cell *matHeaderCellDef>
                                    {{ "applicationLog.column.logger" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let entry" class="mono logger" [matTooltip]="entry.logger">
                                    {{ shortLogger(entry.logger) }}
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="message">
                                <th mat-header-cell *matHeaderCellDef>
                                    {{ "applicationLog.column.message" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let entry" class="message">
                                    {{ entry.message }}
                                    @if (entry.stackTrace) {
                                        <mat-icon
                                            class="has-trace"
                                            [matTooltip]="'applicationLog.hasStackTrace' | transloco"
                                            >bug_report</mat-icon
                                        >
                                    }
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="details">
                                <td mat-cell *matCellDef="let entry" [attr.colspan]="displayedColumns.length">
                                    @if (expanded() === entry) {
                                        <div class="details">
                                            <div class="meta">
                                                <span
                                                    ><strong>{{ "applicationLog.column.logger" | transloco }}:</strong>
                                                    <span class="mono">{{ entry.logger }}</span></span
                                                >
                                                <span
                                                    ><strong>{{ "applicationLog.thread" | transloco }}:</strong>
                                                    <span class="mono">{{ entry.thread }}</span></span
                                                >
                                            </div>
                                            @if (entry.stackTrace) {
                                                <pre>{{ entry.stackTrace }}</pre>
                                            }
                                        </div>
                                    }
                                </td>
                            </ng-container>

                            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                            <tr
                                mat-row
                                *matRowDef="let row; columns: displayedColumns"
                                class="clickable"
                                [class.expanded]="expanded() === row"
                                (click)="toggle(row)"
                            ></tr>
                            <tr
                                mat-row
                                *matRowDef="let row; columns: ['details']"
                                class="details-row"
                                [class.open]="expanded() === row"
                            ></tr>
                        </table>
                    </div>
                }
            </div>
        </div>
    `,
    styles: [
        `
            .log-table {
                width: 100%;
            }

            .nowrap {
                white-space: nowrap;
            }

            .logger {
                font-size: 12px;
                white-space: nowrap;
            }

            .mono {
                font-family: var(--app-mono);
            }

            .message {
                word-break: break-word;
            }

            .has-trace {
                font-size: 16px;
                width: 16px;
                height: 16px;
                vertical-align: text-bottom;
                margin-left: 4px;
                color: var(--app-text-3);
            }

            .clickable.expanded > td {
                background: var(--app-brand-softer);
            }

            .details-row {
                height: 0;
            }

            .details-row td {
                padding: 0;
                border-bottom-width: 0;
            }

            .details-row.open td {
                border-bottom-width: 1px;
            }

            .details {
                padding: 8px 16px 16px;
            }

            .meta {
                display: flex;
                flex-wrap: wrap;
                gap: 8px 24px;
                font-size: 12px;
                color: var(--app-text-2);
                margin-bottom: 8px;
            }

            pre {
                margin: 0;
                padding: 12px;
                background: var(--app-surface-2);
                color: var(--app-text);
                border: 1px solid var(--app-border);
                font-family: var(--app-mono);
                font-size: 11px;
                line-height: 1.5;
                overflow-x: auto;
                border-radius: 8px;
            }
        `,
    ],
})
export class ApplicationLogListComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);

    readonly entries = this.store.selectSignal(ApplicationLogSelectors.selectEntries);
    readonly capacity = this.store.selectSignal(ApplicationLogSelectors.selectCapacity);
    readonly startedAt = this.store.selectSignal(ApplicationLogSelectors.selectStartedAt);
    readonly loading = this.store.selectSignal(ApplicationLogSelectors.selectLoading);
    readonly filter = this.store.selectSignal(ApplicationLogSelectors.selectFilter);
    readonly sending = this.store.selectSignal(ApplicationLogSelectors.selectSending);

    readonly displayedColumns = ["timestamp", "level", "logger", "message"];
    readonly expanded = signal<ApplicationLogEntry | null>(null);

    private readonly searches = new Subject<string>();

    constructor() {
        this.load(this.filter());
        // The search is sent while typing, after a short pause.
        this.searches
            .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed())
            .subscribe(search => this.load({...this.filter(), search}));

        this.actions$
            .pipe(ofType(ApplicationLogActions.sendDailyReportSuccess), takeUntilDestroyed())
            .subscribe(({recipients}) =>
                this.notify(this.transloco.translate("applicationLog.dailyReport.sent", {count: recipients}), false),
            );
        // A report the mail server refused (502) leaves a warning in the log - reloaded, so the page shows it.
        this.actions$
            .pipe(ofType(ApplicationLogActions.sendDailyReportFailure), takeUntilDestroyed())
            .subscribe(({error}) => {
                this.notify(this.transloco.translate("applicationLog.dailyReport.failed", {error}), true);
                this.reload();
            });
    }

    sendDailyReport(): void {
        this.store.dispatch(ApplicationLogActions.sendDailyReport());
    }

    chooseLevel(level: ApplicationLogFilter["level"]): void {
        this.load({...this.filter(), level});
    }

    search(text: string): void {
        this.searches.next(text);
    }

    reload(): void {
        this.load(this.filter());
    }

    toggle(entry: ApplicationLogEntry): void {
        this.expanded.set(this.expanded() === entry ? null : entry);
    }

    /** The class name without its package - the full name is in the tooltip. */
    shortLogger(logger: string): string {
        return logger.substring(logger.lastIndexOf(".") + 1);
    }

    private load(filter: ApplicationLogFilter): void {
        this.expanded.set(null);
        this.store.dispatch(ApplicationLogActions.loadEntries({filter}));
    }

    private notify(message: string, isError: boolean): void {
        this.snackBar.open(message, this.transloco.translate("common.ok"), {
            duration: isError ? 15000 : 5000,
            panelClass: isError ? "error-snackbar" : undefined,
        });
    }
}
