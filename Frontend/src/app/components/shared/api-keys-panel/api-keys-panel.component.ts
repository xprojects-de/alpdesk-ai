import {Component, computed, DestroyRef, effect, inject, input, OnInit, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter} from "rxjs/operators";
import {Clipboard} from "@angular/cdk/clipboard";
import {MatDialog} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatTableModule} from "@angular/material/table";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {ApiKey, TenantScope} from "../../../models/tenant.model";
import * as TenantActions from "../../../store/tenant/tenant.actions";
import * as TenantSelectors from "../../../store/tenant/tenant.selectors";
import * as AreaActions from "../../../store/knowledge-area/knowledge-area.actions";
import * as TaskActions from "../../../store/task/task.actions";
import * as TaskSelectors from "../../../store/task/task.selectors";
import * as AreaSelectors from "../../../store/knowledge-area/knowledge-area.selectors";
import * as McpConnectionActions from "../../../store/mcp-connection/mcp-connection.actions";
import * as McpConnectionSelectors from "../../../store/mcp-connection/mcp-connection.selectors";
import {KeyChoices, KeyRequest, keySummary, KeySummary} from "../../../utils/key-form.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {DialogSave, DialogSaveSpec} from "../dialog-save";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {KeyDialogComponent, KeyDialogData} from "../key-dialog/key-dialog.component";
import {KeyRightsComponent} from "../key-rights/key-rights.component";
import {tenantWebSearch} from "../tenant-web-search";

/**
 * Lists, creates and revokes one tenant's API keys for its client software: the tenant admin's page "API-Keys" (scope
 * "self") or the admin's dialog in the tenant list (a tenant's id). A newly created key is shown here exactly once
 * (held only in this component's signal, never in the store) - after leaving the page or dialog it cannot be
 * retrieved again, only replaced.
 */
@Component({
    selector: "app-api-keys-panel",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        MatButtonModule,
        MatIconModule,
        MatTableModule,
        MatTooltipModule,
        MatProgressBarModule,
        EmptyStateComponent,
        KeyRightsComponent,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "apiKeys.title" | transloco }}</span>
                <span class="muted">{{ "apiKeys.subtitle" | transloco }}</span>
                <span class="spacer"></span>
                <button mat-flat-button (click)="openCreate()" [disabled]="loading()">
                    <mat-icon>add</mat-icon>
                    {{ "apiKeys.add" | transloco }}
                </button>
            </div>
            <div class="app-panel-body">
                <p class="hint">
                    {{ "apiKeys.hint.before" | transloco }}
                    <code class="mono">Authorization: Bearer &lt;API-Key&gt;</code
                    >{{ "apiKeys.hint.after" | transloco }}
                </p>

                @if (newKey(); as key) {
                    <div class="new-key">
                        <div class="new-key-title">
                            <mat-icon>warning</mat-icon>
                            {{ "apiKeys.newKey" | transloco }}
                        </div>
                        <div class="new-key-value">
                            <code>{{ key }}</code>
                            <button
                                mat-icon-button
                                (click)="copy(key)"
                                [matTooltip]="'apiKeys.copyTooltip' | transloco"
                            >
                                <mat-icon>content_copy</mat-icon>
                            </button>
                        </div>
                    </div>
                }
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (keys().length === 0 && !loading()) {
                <app-empty-state
                    icon="key"
                    [title]="'apiKeys.empty.title' | transloco"
                    [text]="'apiKeys.empty.text' | transloco"
                />
            } @else if (keys().length > 0) {
                <!-- While the first answer is loading: neither an empty table nor the settings of a key not yet known. -->
                <div class="table-container">
                    <table mat-table [dataSource]="keys()" [trackBy]="trackById" class="keys-table">
                        <ng-container matColumnDef="name">
                            <th mat-header-cell *matHeaderCellDef>{{ "apiKeys.column.name" | transloco }}</th>
                            <td mat-cell *matCellDef="let key" class="cell-title">{{ key.name }}</td>
                        </ng-container>
                        <ng-container matColumnDef="prefix">
                            <th mat-header-cell *matHeaderCellDef>{{ "apiKeys.column.key" | transloco }}</th>
                            <td mat-cell *matCellDef="let key">
                                <span class="mono">{{ key.keyPrefix }}…</span>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="rights">
                            <th mat-header-cell *matHeaderCellDef>{{ "keyRights.column" | transloco }}</th>
                            <td mat-cell *matCellDef="let key">
                                <app-key-rights [summary]="summary(key)" [keepsHistory]="key.keepsHistory" />
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="lastUsedAt">
                            <th mat-header-cell *matHeaderCellDef>{{ "apiKeys.column.lastUsed" | transloco }}</th>
                            <td mat-cell *matCellDef="let key" class="num muted">
                                @if (key.lastUsedAt) {
                                    {{ key.lastUsedAt | translocoDate: {dateStyle: "medium", timeStyle: "short"} }}
                                } @else {
                                    {{ "apiKeys.never" | transloco }}
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let key" class="actions-column">
                                <button
                                    mat-icon-button
                                    (click)="openEdit(key)"
                                    [disabled]="loading()"
                                    [matTooltip]="'keyRights.edit' | transloco"
                                    [attr.aria-label]="'keyRights.edit' | transloco"
                                >
                                    <mat-icon>edit</mat-icon>
                                </button>
                                <button
                                    mat-icon-button
                                    class="danger"
                                    (click)="confirmRevoke(key)"
                                    [matTooltip]="'apiKeys.revoke' | transloco"
                                >
                                    <mat-icon>delete</mat-icon>
                                </button>
                            </td>
                        </ng-container>
                        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                        <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                    </table>
                </div>
            }
        </div>
    `,
    styles: [
        `
            .hint {
                color: var(--app-text-2);
                font-size: 13px;
                margin: 0;
            }

            .new-key {
                margin-top: 12px;
            }

            .new-key {
                border: 1px solid color-mix(in oklab, var(--app-warn) 45%, var(--app-border));
                background: color-mix(in oklab, var(--app-warn) 10%, var(--app-surface));
                border-radius: 10px;
                padding: 12px 14px;
            }

            .new-key-title {
                display: flex;
                align-items: center;
                gap: 8px;
                font-weight: 500;
                margin-bottom: 8px;
            }

            .new-key-title mat-icon {
                color: var(--app-warn);
            }

            .new-key-value {
                display: flex;
                align-items: center;
                gap: 8px;
            }

            .new-key-value code {
                flex: 1 1 auto;
                word-break: break-all;
                background: var(--app-surface);
                border: 1px solid var(--app-border);
                font-family: var(--app-mono);
                font-size: 12px;
                padding: 8px;
                border-radius: 6px;
            }

            .keys-table {
                width: 100%;
                min-width: min(640px, 80vw);
            }

            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class ApiKeysPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private clipboard = inject(Clipboard);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);
    readonly scope = input.required<TenantScope>();
    /** Only a tenant with the web search gets its choice. */
    readonly webSearch = tenantWebSearch(this.scope);
    /** The tenant may have scheduled tasks: the keys get the choice of the tasks they may start. */
    readonly withTasks = input(false);

    readonly keys = this.store.selectSignal(TenantSelectors.selectApiKeys);
    readonly loading = this.store.selectSignal(TenantSelectors.selectApiKeysLoading);
    readonly newKey = signal<string | null>(null);
    readonly displayedColumns = ["name", "prefix", "rights", "lastUsedAt", "actions"];
    readonly tasks = this.store.selectSignal(TaskSelectors.selectTasks);
    /** The tenant's connected software - each key chooses what its chat questions may use (roadmap point 42). */
    readonly connections = this.store.selectSignal(McpConnectionSelectors.selectConnections);
    readonly areas = this.store.selectSignal(AreaSelectors.selectAreas);
    readonly trackById = (_index: number, key: ApiKey) => key.id;

    /** What the dialog offers: only what the tenant has. */
    private readonly choices = computed<KeyChoices>(() => ({
        areas: this.areas().length > 0,
        software: this.connections().length > 0,
        tasks: this.withTasks() && this.tasks().length > 0,
        webSearch: this.webSearch(),
        keepsHistory: true,
        description: false,
        doors: false,
        changing: false,
    }));

    constructor() {
        // The account may say only after the page opened that the tenant may have tasks.
        effect(() => {
            if (this.withTasks()) {
                this.store.dispatch(TaskActions.loadTasks({scope: this.scope()}));
            }
        });
        this.actions$
            .pipe(
                ofType(TenantActions.createApiKeySuccess),
                filter(({scope}) => scope === this.scope()),
                takeUntilDestroyed(),
            )
            .subscribe(({plainKey}) => this.newKey.set(plainKey));
        this.actions$.pipe(ofType(TenantActions.revokeApiKeySuccess), takeUntilDestroyed()).subscribe(() =>
            this.snackBar.open(this.translate("apiKeys.snack.revoked"), this.translate("common.ok"), {
                duration: 3000,
            }),
        );
        this.actions$
            .pipe(
                ofType(
                    TenantActions.createApiKeyFailure,
                    TenantActions.updateApiKeyFailure,
                    TenantActions.revokeApiKeyFailure,
                ),
                takeUntilDestroyed(),
            )
            .subscribe(({error}) =>
                this.snackBar.open(this.translate("apiKeys.snack.error", {error}), this.translate("common.ok"), {
                    duration: 10000,
                    panelClass: "error-snackbar",
                }),
            );
    }

    ngOnInit(): void {
        this.store.dispatch(AreaActions.loadAreas({scope: this.scope()}));
        this.store.dispatch(TenantActions.loadApiKeys({scope: this.scope()}));
        this.store.dispatch(McpConnectionActions.loadConnections({scope: this.scope()}));
    }

    summary(key: ApiKey): KeySummary {
        return keySummary(
            key,
            {areas: this.areas(), software: this.connections(), tasks: this.tasks()},
            this.choices(),
        );
    }

    openCreate(): void {
        this.openDialog(null, {
            action: request => TenantActions.createApiKey({scope: this.scope(), request}),
            success: [TenantActions.createApiKeySuccess],
            failure: TenantActions.createApiKeyFailure,
        });
    }

    openEdit(key: ApiKey): void {
        this.openDialog(key, {
            action: request => TenantActions.updateApiKey({scope: this.scope(), keyId: key.id, request}),
            success: [TenantActions.updateApiKeySuccess],
            failure: TenantActions.updateApiKeyFailure,
        });
    }

    copy(key: string): void {
        this.snackBar.open(
            this.translate(this.clipboard.copy(key) ? "apiKeys.snack.copied" : "apiKeys.snack.copyFailed"),
            this.translate("common.ok"),
            {duration: 3000},
        );
    }

    confirmRevoke(key: ApiKey): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.translate("apiKeys.confirmRevoke", {name: key.name}),
                    confirmLabel: this.translate("apiKeys.revoke"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(TenantActions.revokeApiKey({scope: this.scope(), keyId: key.id})));
    }

    private openDialog(key: ApiKey | null, spec: DialogSaveSpec<KeyRequest>): void {
        this.dialogSave.open<KeyDialogComponent, KeyDialogData, KeyRequest>(
            KeyDialogComponent,
            {
                width: "560px",
                data: {
                    kind: "api",
                    key,
                    choices: this.choices(),
                    areas: this.areas(),
                    software: this.connections().map(connection => ({id: connection.id, label: connection.name})),
                    tasks: this.tasks(),
                },
            },
            spec,
        );
    }

    private translate(key: string, params?: Record<string, unknown>): string {
        return this.transloco.translate(key, params);
    }
}
