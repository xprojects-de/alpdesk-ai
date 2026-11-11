import {Component, DestroyRef, inject, input, OnDestroy, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {ActionCreator, Creator, Store} from "@ngrx/store";
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
import {KnowledgeSource, KnowledgeSourceRequest} from "../../../models/knowledge-source.model";
import {TenantScope} from "../../../models/tenant.model";
import {injectLocale} from "../../../i18n/locale";
import * as DocumentActions from "../../../store/document/document.actions";
import * as FileStoreActions from "../../../store/file-store/file-store.actions";
import * as FileStoreSelectors from "../../../store/file-store/file-store.selectors";
import * as AreaSelectors from "../../../store/knowledge-area/knowledge-area.selectors";
import * as SourceActions from "../../../store/knowledge-source/knowledge-source.actions";
import * as SourceSelectors from "../../../store/knowledge-source/knowledge-source.selectors";
import {formatBytes} from "../../../utils/document.util";
import {COMPANY_KNOWLEDGE_KEY} from "../../../utils/knowledge-area.util";
import {sourceErrorParams, sourceStatus} from "../../../utils/knowledge-source.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {DialogSave} from "../dialog-save";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {KnowledgeSourceDialogComponent, KnowledgeSourceDialogData} from "./knowledge-source-dialog.component";
import {RemoveSourceDialogComponent, SourceRemoval} from "./remove-source-dialog.component";
import {SourceFilesDialogComponent, SourceFilesDialogData} from "./source-files-dialog.component";

/**
 * The knowledge sources of one tenant (TenantScope): folders of its file stores synced into the company knowledge or
 * an area. Saving only lists a folder; "Jetzt synchronisieren" reads everything open after a confirmation with the
 * numbers, the schedule at most a limit per run. While a source runs, the list is reloaded from the app every few
 * seconds - the store itself sees a connection only when a run works.
 */
@Component({
    selector: "app-knowledge-sources-panel",
    imports: [
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatProgressBarModule,
        EmptyStateComponent,
        TranslocoPipe,
        TranslocoDatePipe,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "knowledgeSources.panel.title" | transloco }}</span>
                <span class="muted">{{ "knowledgeSources.panel.intro" | transloco }}</span>
                <span class="spacer"></span>
                @if (stores().length > 0) {
                    <button mat-stroked-button (click)="openCreate()">
                        <mat-icon>add</mat-icon>
                        {{ "knowledgeSources.panel.add" | transloco }}
                    </button>
                }
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (sources().length === 0) {
                @if (!loading()) {
                    <app-empty-state
                        icon="drive_folder_upload"
                        [title]="'knowledgeSources.panel.emptyTitle' | transloco"
                        [text]="
                            (stores().length > 0
                                ? 'knowledgeSources.panel.emptyText'
                                : 'knowledgeSources.panel.emptyNoStore'
                            ) | transloco
                        "
                    >
                        @if (stores().length > 0) {
                            <button mat-flat-button (click)="openCreate()">
                                <mat-icon>add</mat-icon>
                                {{ "knowledgeSources.panel.add" | transloco }}
                            </button>
                        }
                    </app-empty-state>
                }
            } @else {
                <div class="sources">
                    @for (source of sources(); track source.id) {
                        @let status = statusOf(source);
                        <div class="source">
                            <div class="source-head">
                                <span class="cell-main">{{ source.name }}</span>
                                <span class="app-chip" [class]="status.chip">
                                    {{ status.label | transloco }}
                                    @if (status.since) {
                                        {{ status.since | translocoDate: {dateStyle: "medium", timeStyle: "short"} }}
                                    }
                                </span>
                                @if (!source.active) {
                                    <span class="app-tag">{{ "knowledgeSources.panel.paused" | transloco }}</span>
                                }
                                <span class="spacer"></span>
                                @if (source.running) {
                                    <button mat-stroked-button (click)="cancel(source)">
                                        <mat-icon>stop</mat-icon>
                                        {{ "knowledgeSources.panel.cancel" | transloco }}
                                    </button>
                                } @else {
                                    <button mat-stroked-button (click)="confirmRun(source)">
                                        <mat-icon>sync</mat-icon>
                                        {{ "knowledgeSources.panel.run" | transloco }}
                                    </button>
                                }
                                <button
                                    mat-icon-button
                                    [matMenuTriggerFor]="menu"
                                    [attr.aria-label]="'common.more' | transloco"
                                >
                                    <mat-icon>more_vert</mat-icon>
                                </button>
                                <mat-menu #menu="matMenu">
                                    <button mat-menu-item (click)="openEdit(source)">
                                        <mat-icon>edit</mat-icon>
                                        {{ "common.edit" | transloco }}
                                    </button>
                                    <button mat-menu-item (click)="openFiles(source)">
                                        <mat-icon>report</mat-icon>
                                        {{ "knowledgeSources.panel.files" | transloco }}
                                    </button>
                                    <button mat-menu-item class="danger" (click)="confirmRemove(source)">
                                        <mat-icon>delete</mat-icon>
                                        {{ "common.remove" | transloco }}
                                    </button>
                                </mat-menu>
                            </div>

                            <div class="meta">
                                <span>{{ "knowledgeSources.panel.from" | transloco }}</span>
                                <span
                                    ><span class="mono">{{ source.fileStore }}</span> /
                                    <span class="mono">{{ source.folder || "/" }}</span>
                                    @if (source.pattern) {
                                        - <span class="mono">{{ source.pattern }}</span>
                                    }
                                </span>
                                <span>{{ "knowledgeSources.panel.into" | transloco }}</span>
                                <span>{{ source.area ?? (companyKnowledge | transloco) }}</span>
                                <span>{{ "knowledgeSources.panel.schedule" | transloco }}</span>
                                <span>
                                    {{ "knowledgeSources.schedule." + source.schedule | transloco }}
                                    @if (source.nextRunAt) {
                                        - {{ "knowledgeSources.panel.next" | transloco }}
                                        {{
                                            source.nextRunAt | translocoDate: {dateStyle: "medium", timeStyle: "short"}
                                        }}
                                    }
                                </span>
                                @if (source.lastFinishedAt) {
                                    <span>{{ "knowledgeSources.panel.lastRun" | transloco }}</span>
                                    <span>
                                        {{
                                            source.lastFinishedAt
                                                | translocoDate: {dateStyle: "medium", timeStyle: "short"}
                                        }}
                                        -
                                        {{
                                            "knowledgeSources.panel.numbers"
                                                | transloco
                                                    : {
                                                          added: source.lastAdded,
                                                          changed: source.lastChanged,
                                                          deleted: source.lastDeleted,
                                                          refused: source.lastRefused,
                                                      }
                                        }}
                                        @if (source.lastSkipped > 0) {
                                            -
                                            {{
                                                "knowledgeSources.panel.skipped"
                                                    | transloco: {count: source.lastSkipped}
                                            }}
                                        }
                                    </span>
                                }
                                @if (source.openFiles > 0) {
                                    <span>{{ "knowledgeSources.panel.open" | transloco }}</span>
                                    <span
                                        ><b>{{
                                            "knowledgeSources.panel.openFiles"
                                                | transloco: {count: source.openFiles, size: size(source.openBytes)}
                                        }}</b></span
                                    >
                                }
                                @if (source.lastError && !source.running) {
                                    <span>{{ "knowledgeSources.panel.error" | transloco }}</span>
                                    <span class="error">{{
                                        "errors.backend." + source.lastError | transloco: errorParams(source)
                                    }}</span>
                                }
                            </div>
                            @if (source.running) {
                                <mat-progress-bar mode="indeterminate" />
                            }
                        </div>
                    }
                </div>
            }
        </div>
    `,
    styles: [
        `
            .source {
                padding: 12px 14px;
                border-top: 1px solid var(--app-border);
            }

            .source-head {
                display: flex;
                align-items: center;
                gap: 8px;
                flex-wrap: wrap;
            }

            .meta {
                display: grid;
                grid-template-columns: max-content 1fr;
                gap: 4px 16px;
                margin-top: 8px;
                color: var(--app-text-2);
                font-size: 13px;
            }

            .mono {
                font-family: var(--app-mono);
                color: var(--app-text);
                overflow-wrap: anywhere;
            }

            .error {
                color: var(--app-err);
            }

            mat-progress-bar {
                margin-top: 8px;
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class KnowledgeSourcesPanelComponent implements OnInit, OnDestroy {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);
    private readonly locale = injectLocale();

    readonly scope = input.required<TenantScope>();

    readonly sources = this.store.selectSignal(SourceSelectors.selectSources);
    readonly loading = this.store.selectSignal(SourceSelectors.selectSourcesLoading);
    readonly stores = this.store.selectSignal(FileStoreSelectors.selectStores);
    private readonly areas = this.store.selectSignal(AreaSelectors.selectAreas);
    readonly statusOf = sourceStatus;

    errorParams(source: KnowledgeSource): Record<string, string> {
        return sourceErrorParams(
            source,
            this.stores().find(store => store.id === source.fileStoreId),
        );
    }
    readonly companyKnowledge = COMPANY_KNOWLEDGE_KEY;
    /** The sources that ran at the last reload - one that ended has new documents for the list below. */
    private running = new Set<number>();

    constructor() {
        this.notifyOn(SourceActions.createSourceSuccess, () =>
            this.transloco.translate("knowledgeSources.snack.created"),
        );
        this.notifyOn(SourceActions.deleteSourceSuccess, () =>
            this.transloco.translate("knowledgeSources.snack.deleted"),
        );
        this.notifyOn(SourceActions.runSourceSuccess, () => this.transloco.translate("knowledgeSources.snack.started"));
        for (const failure of [
            SourceActions.createSourceFailure,
            SourceActions.updateSourceFailure,
            SourceActions.deleteSourceFailure,
            SourceActions.runSourceFailure,
            SourceActions.cancelSourceFailure,
        ]) {
            this.notifyOn(
                failure,
                ({error}) => this.transloco.translate("knowledgeSources.snack.error", {error}),
                true,
            );
        }
        // While a source runs - and once it ended - its documents appear in the list below.
        this.actions$
            .pipe(
                ofType(SourceActions.loadSourcesSuccess),
                filter(({scope}) => scope === this.scope()),
                takeUntilDestroyed(),
            )
            .subscribe(({sources}) => this.followRuns(sources));
        // A run by hand may end before the next reload sees it running - its documents are reloaded all the same.
        this.actions$
            .pipe(
                ofType(SourceActions.runSourceSuccess),
                filter(({scope}) => scope === this.scope()),
                takeUntilDestroyed(),
            )
            .subscribe(({source}) => this.running.add(source.id));
        // A removed source's documents went with it, or became ordinary ones.
        this.actions$
            .pipe(
                ofType(SourceActions.deleteSourceSuccess),
                filter(({scope}) => scope === this.scope()),
                takeUntilDestroyed(),
            )
            .subscribe(() => this.store.dispatch(DocumentActions.pollDocuments({scope: this.scope()})));
    }

    ngOnInit(): void {
        this.store.dispatch(SourceActions.loadSources({scope: this.scope()}));
        this.store.dispatch(FileStoreActions.loadStores({scope: this.scope()}));
        this.store.dispatch(SourceActions.startPolling({scope: this.scope()}));
    }

    ngOnDestroy(): void {
        this.store.dispatch(SourceActions.stopPolling());
    }

    size(bytes: number): string {
        return formatBytes(bytes, this.locale());
    }

    openCreate(): void {
        this.openDialog(null);
    }

    openEdit(source: KnowledgeSource): void {
        this.openDialog(source);
    }

    openFiles(source: KnowledgeSource): void {
        this.dialog.open<SourceFilesDialogComponent, SourceFilesDialogData>(SourceFilesDialogComponent, {
            data: {scope: this.scope(), source},
        });
    }

    /** A run by hand reads everything open - asked first with the numbers, scanned pages cost. */
    confirmRun(source: KnowledgeSource): void {
        const message =
            source.openFiles > 0
                ? this.transloco.translate("knowledgeSources.run.message", {
                      count: source.openFiles,
                      size: this.size(source.openBytes),
                  })
                : this.transloco.translate("knowledgeSources.run.messageNothingOpen");
        this.confirm({
            title: this.transloco.translate("knowledgeSources.run.title", {name: source.name}),
            message,
            confirmLabel: this.transloco.translate("knowledgeSources.panel.run"),
        }).subscribe(() => this.store.dispatch(SourceActions.runSource({scope: this.scope(), sourceId: source.id})));
    }

    cancel(source: KnowledgeSource): void {
        this.store.dispatch(SourceActions.cancelSource({scope: this.scope(), sourceId: source.id}));
    }

    confirmRemove(source: KnowledgeSource): void {
        this.dialog
            .open<RemoveSourceDialogComponent, string, SourceRemoval>(RemoveSourceDialogComponent, {
                width: "600px",
                data: source.name,
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(removal =>
                this.store.dispatch(
                    SourceActions.deleteSource({
                        scope: this.scope(),
                        sourceId: source.id,
                        keepDocuments: removal === "keep",
                    }),
                ),
            );
    }

    private openDialog(source: KnowledgeSource | null): void {
        this.dialogSave.open<KnowledgeSourceDialogComponent, KnowledgeSourceDialogData, KnowledgeSourceRequest>(
            KnowledgeSourceDialogComponent,
            {data: {scope: this.scope(), source, sources: this.sources(), stores: this.stores(), areas: this.areas()}},
            source === null
                ? {
                      action: request => SourceActions.createSource({scope: this.scope(), request}),
                      success: [SourceActions.createSourceSuccess],
                      failure: SourceActions.createSourceFailure,
                  }
                : {
                      action: request =>
                          SourceActions.updateSource({scope: this.scope(), sourceId: source.id, request}),
                      success: [SourceActions.updateSourceSuccess],
                      failure: SourceActions.updateSourceFailure,
                  },
        );
    }

    /** Reloads the documents while a source runs and once more when it ended - they are its work. */
    private followRuns(sources: KnowledgeSource[]): void {
        const now = new Set(sources.filter(source => source.running).map(source => source.id));
        if (now.size > 0 || [...this.running].some(id => !now.has(id))) {
            this.store.dispatch(DocumentActions.pollDocuments({scope: this.scope()}));
        }
        this.running = now;
    }

    private confirm(data: ConfirmDialogData) {
        return this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {width: "520px", data})
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef));
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.transloco.translate("common.ok"), {
                duration: isError ? 10000 : 4000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }
}
