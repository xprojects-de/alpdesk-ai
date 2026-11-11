import {Component, computed, DestroyRef, effect, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {Clipboard} from "@angular/cdk/clipboard";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {DateFormatOptions, TranslocoDatePipe} from "@jsverse/transloco-locale";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog} from "@angular/material/dialog";
import {MatIconModule} from "@angular/material/icon";
import {MatMenuModule} from "@angular/material/menu";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTableModule} from "@angular/material/table";
import {MatTooltipModule} from "@angular/material/tooltip";
import {McpConnection, McpKey} from "../../../models/mcp.model";
import {TenantScope} from "../../../models/tenant.model";
import * as McpKeyActions from "../../../store/mcp-key/mcp-key.actions";
import * as McpKeySelectors from "../../../store/mcp-key/mcp-key.selectors";
import * as AreaActions from "../../../store/knowledge-area/knowledge-area.actions";
import * as AreaSelectors from "../../../store/knowledge-area/knowledge-area.selectors";
import * as McpConnectionActions from "../../../store/mcp-connection/mcp-connection.actions";
import * as McpConnectionSelectors from "../../../store/mcp-connection/mcp-connection.selectors";
import * as TaskActions from "../../../store/task/task.actions";
import * as TaskSelectors from "../../../store/task/task.selectors";
import * as BrandingActions from "../../../store/branding/branding.actions";
import * as BrandingSelectors from "../../../store/branding/branding.selectors";
import {KeyChoices, KeyRequest, keySummary, KeySummary} from "../../../utils/key-form.util";
import {
    backendOrigin,
    a2aAddresses,
    claudeCodeCommand,
    claudeConnector,
    ClaudeConnector,
    copilotConfig,
    MCP_KEY_PLACEHOLDER,
} from "../../../utils/mcp.util";
import {environment} from "../../../../environments/environment";
import {ClaudeConnectorComponent} from "../claude-connector/claude-connector.component";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {DialogSave, DialogSaveSpec} from "../dialog-save";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {KeyDialogComponent, KeyDialogData} from "../key-dialog/key-dialog.component";
import {KeyRightsComponent} from "../key-rights/key-rights.component";
import {tenantWebSearch} from "../tenant-web-search";

/**
 * The tenant's MCP keys (docs/architecture/mcp.md "MCP keys"): with one an MCP client such as Claude Code or Cowork asks
 * the tenant's assistant and searches its documents - the company knowledge and the key's areas, with the web search
 * where the key may. Several keys, each composed for a purpose and handed on by the admin, like the client software's
 * API keys. A new or renewed key is shown once, with the command that connects Claude Code and the connector's settings
 * for claude.ai and Cowork; command and Copilot configuration name the server after the tenant's brand (`muster-ai`
 * for "Muster AI"). The tenant admin's page "API-Keys" and the admin's tenant page of the same name.
 */
/** A software whose changing tools a key's MCP client may call: an MCP server that goes directly to MCP clients. */
function changeable(connection: McpConnection): boolean {
    return connection.passesThrough && connection.protocol === "MCP";
}

@Component({
    selector: "app-mcp-key-panel",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatProgressBarModule,
        MatTooltipModule,
        MatTableModule,
        ClaudeConnectorComponent,
        EmptyStateComponent,
        KeyRightsComponent,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "mcpKey.title" | transloco }}</span>
                <span class="muted">{{ "mcpKey.subtitle" | transloco }}</span>
                <span class="spacer"></span>
                <button mat-flat-button (click)="openCreate()" [disabled]="loading()">
                    <mat-icon>add</mat-icon>
                    {{ "mcpKey.generate" | transloco }}
                </button>
            </div>

            <div class="app-panel-body">
                <p class="hint">{{ "mcpKey.hint" | transloco }}</p>

                @if (created(); as newKey) {
                    <div class="created">
                        <div class="created-title">{{ "mcpKey.created.title" | transloco: {name: newKey.name} }}</div>
                        <div class="code-row">
                            <code>{{ newKey.plainKey }}</code>
                            <button
                                mat-icon-button
                                (click)="copy(newKey.plainKey, 'mcpKey.copied.key')"
                                [matTooltip]="'mcpKey.copyKey' | transloco"
                                [attr.aria-label]="'mcpKey.copyKey' | transloco"
                            >
                                <mat-icon>content_copy</mat-icon>
                            </button>
                        </div>
                        @if (newKey.a2a) {
                            <div class="created-title">{{ "mcpKey.a2a.address" | transloco }}</div>
                            <div class="code-row">
                                <code>{{ a2a.address }}</code>
                            </div>
                            <div class="created-title">{{ "mcpKey.a2a.card" | transloco }}</div>
                            <div class="code-row">
                                <code>{{ a2a.card }}</code>
                            </div>
                        }
                        @if (newKey.mcp) {
                            <div class="created-title">{{ "mcpKey.created.claudeCode" | transloco }}</div>
                            <div class="code-row">
                                <code>{{ command(newKey.plainKey) }}</code>
                                <button
                                    mat-icon-button
                                    (click)="copy(command(newKey.plainKey), 'mcpKey.copied.command')"
                                    [matTooltip]="'mcpKey.copyCommand' | transloco"
                                    [attr.aria-label]="'mcpKey.copyCommand' | transloco"
                                >
                                    <mat-icon>content_copy</mat-icon>
                                </button>
                            </div>
                            <app-claude-connector [connector]="connector(newKey.plainKey)" />
                        }
                        <button mat-stroked-button (click)="dismiss()">{{ "mcpKey.done" | transloco }}</button>
                    </div>
                }
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (keys().length === 0 && !loading()) {
                <app-empty-state
                    icon="key"
                    [title]="'mcpKey.empty.title' | transloco"
                    [text]="'mcpKey.empty.text' | transloco"
                />
            } @else if (keys().length > 0) {
                <!-- While the first answer is loading: neither an empty table nor the settings of a key not yet known. -->
                <div class="table-container">
                    <table mat-table [dataSource]="keys()" [trackBy]="trackById" class="keys-table">
                        <ng-container matColumnDef="name">
                            <th mat-header-cell *matHeaderCellDef>{{ "mcpKey.column.name" | transloco }}</th>
                            <td mat-cell *matCellDef="let key" class="cell-title">
                                {{ key.name }}
                                @if (key.mcp) {
                                    <span class="app-tag">{{ "mcpKey.door.mcp" | transloco }}</span>
                                }
                                @if (key.a2a) {
                                    <span class="app-tag">{{ "mcpKey.door.a2a" | transloco }}</span>
                                }
                                @if (key.description; as description) {
                                    <div class="key-description" [title]="description">{{ description }}</div>
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="prefix">
                            <th mat-header-cell *matHeaderCellDef>{{ "mcpKey.column.key" | transloco }}</th>
                            <td mat-cell *matCellDef="let key">
                                <span class="mono">{{ key.keyPrefix }}…</span>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="rights">
                            <th mat-header-cell *matHeaderCellDef>{{ "keyRights.column" | transloco }}</th>
                            <td mat-cell *matCellDef="let key">
                                <app-key-rights [summary]="summary(key)" />
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="lastUsedAt">
                            <th mat-header-cell *matHeaderCellDef>{{ "mcpKey.column.lastUsed" | transloco }}</th>
                            <td mat-cell *matCellDef="let key" class="num muted">
                                @if (key.lastUsedAt) {
                                    {{ key.lastUsedAt | translocoDate: dateFormat }}
                                } @else {
                                    {{ "mcpKey.neverUsed" | transloco }}
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let key" class="actions-column">
                                <button
                                    mat-icon-button
                                    [matMenuTriggerFor]="keyMenu"
                                    [disabled]="loading()"
                                    [attr.aria-label]="'keyRights.actionsFor' | transloco: {name: key.name}"
                                >
                                    <mat-icon>more_vert</mat-icon>
                                </button>
                                <mat-menu #keyMenu="matMenu" xPosition="before">
                                    <button mat-menu-item (click)="openEdit(key)">
                                        <mat-icon>edit</mat-icon>
                                        {{ "keyRights.edit" | transloco }}
                                    </button>
                                    <button mat-menu-item (click)="confirmRenew(key)">
                                        <mat-icon>autorenew</mat-icon>
                                        {{ "mcpKey.regenerate" | transloco }}
                                    </button>
                                    <button mat-menu-item class="danger" (click)="confirmDelete(key)">
                                        <mat-icon>delete</mat-icon>
                                        {{ "mcpKey.remove" | transloco }}
                                    </button>
                                </mat-menu>
                            </td>
                        </ng-container>
                        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                        <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                    </table>
                </div>

                <div class="app-panel-body">
                    <div class="created-title">
                        {{ "mcpKey.copilot" | transloco }} <code class="mono">.vscode/mcp.json</code>
                    </div>
                    <div class="code-row">
                        <pre>{{ copilot() }}</pre>
                        <button
                            mat-icon-button
                            (click)="copy(copilot(), 'mcpKey.copied.config')"
                            [matTooltip]="'mcpKey.copyConfig' | transloco"
                            [attr.aria-label]="'mcpKey.copyConfig' | transloco"
                        >
                            <mat-icon>content_copy</mat-icon>
                        </button>
                    </div>
                    <p class="hint">{{ "mcpKey.vscodeHint" | transloco }}</p>
                    <app-claude-connector [connector]="connector(placeholder)" />
                </div>
            }
        </div>
    `,
    styles: [
        `
            .key-description {
                color: var(--app-text-2);
                font-size: 12.5px;
                font-weight: normal;
                max-width: 360px;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }

            .hint {
                color: var(--app-text-2);
                font-size: 13px;
                margin: 0 0 12px;
            }

            .hint:last-child {
                margin-bottom: 0;
            }

            .created {
                border: 1px solid var(--app-brand);
                background: var(--app-brand-softer);
                border-radius: 10px;
                padding: 12px 14px;
                margin: 0 0 16px;
            }

            .created-title {
                font-weight: 500;
                margin: 4px 0;
            }

            .code-row {
                display: flex;
                align-items: center;
                gap: 4px;
                margin-bottom: 8px;
            }

            .code-row pre,
            .code-row code {
                flex: 1;
                min-width: 0;
                background: var(--app-surface-2);
                border: 1px solid var(--app-border);
                padding: 6px 8px;
                border-radius: 6px;
                font-family: var(--app-mono);
                font-size: 12px;
            }

            .code-row pre {
                overflow-x: auto;
                margin: 0;
            }

            .code-row code {
                word-break: break-all;
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
export class McpKeyPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private clipboard = inject(Clipboard);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);

    readonly scope = input.required<TenantScope>();
    /** Only a tenant with the web search sees the keys' switch. */
    readonly tenantHasWebSearch = tenantWebSearch(this.scope);
    /** The tenant may have scheduled tasks: the keys get the column of the tasks they may list and start. */
    readonly withTasks = input(false);

    readonly keys = this.store.selectSignal(McpKeySelectors.selectMcpKeys);
    readonly loading = this.store.selectSignal(McpKeySelectors.selectMcpKeysLoading);
    readonly created = this.store.selectSignal(McpKeySelectors.selectCreatedMcpKey);
    readonly areas = this.store.selectSignal(AreaSelectors.selectAreas);
    /** The tenant's connected software - each key chooses what it may use (roadmap point 41). */
    readonly connections = this.store.selectSignal(McpConnectionSelectors.selectConnections);
    readonly tasks = this.store.selectSignal(TaskSelectors.selectTasks);
    readonly dateFormat: DateFormatOptions = {dateStyle: "medium", timeStyle: "short"};
    readonly placeholder = MCP_KEY_PLACEHOLDER;
    readonly trackById = (_index: number, key: McpKey) => key.id;
    readonly displayedColumns = ["name", "prefix", "rights", "lastUsedAt", "actions"];

    /** What the dialog offers: only what the tenant has. */
    private readonly choices = computed<KeyChoices>(() => ({
        areas: this.areas().length > 0,
        software: this.connections().length > 0,
        tasks: this.withTasks() && this.tasks().length > 0,
        webSearch: this.tenantHasWebSearch(),
        keepsHistory: false,
        description: true,
        doors: true,
        changing: this.connections().some(changeable),
    }));

    private readonly origin = backendOrigin(environment.apiUrl, window.location.origin);
    /** Where a key with A2A reaches the assistant, and its card - under the context path, as the backend writes them. */
    readonly a2a = a2aAddresses(this.origin);
    private readonly shownBranding = this.store.selectSignal(BrandingSelectors.selectBranding);
    private readonly adminBranding = this.store.selectSignal(BrandingSelectors.selectAdminBranding);
    private readonly installationName = this.store.selectSignal(BrandingSelectors.selectInstallationName);
    /** The tenant's brand: the tenant admin sees the tenant's look; the admin reads that tenant's, else the default. */
    private readonly brand = computed(() => {
        if (this.scope() === "self") {
            return this.shownBranding().name;
        }
        const admin = this.adminBranding();
        return admin?.name ?? admin?.defaultName ?? this.installationName();
    });
    readonly copilot = computed(() => copilotConfig(this.origin, this.brand()));

    constructor() {
        // The account may say only after the page opened that the tenant may have tasks.
        effect(() => {
            if (this.withTasks()) {
                this.store.dispatch(TaskActions.loadTasks({scope: this.scope()}));
            }
        });
        // A new key is shown once: leaving the page without "Erledigt" must not show it again on the way back.
        this.destroyRef.onDestroy(() => this.store.dispatch(McpKeyActions.dismissCreatedMcpKey()));
        this.notifyOn(McpKeyActions.deleteMcpKeySuccess, () => this.translate("mcpKey.snack.removed"));
        for (const failure of [
            McpKeyActions.createMcpKeyFailure,
            McpKeyActions.updateMcpKeyFailure,
            McpKeyActions.renewMcpKeyFailure,
            McpKeyActions.deleteMcpKeyFailure,
        ]) {
            this.notifyOn(failure, ({error}) => this.translate("mcpKey.snack.error", {error}), true);
        }
    }

    ngOnInit(): void {
        this.store.dispatch(McpKeyActions.loadMcpKeys({scope: this.scope()}));
        this.store.dispatch(AreaActions.loadAreas({scope: this.scope()}));
        this.store.dispatch(McpConnectionActions.loadConnections({scope: this.scope()}));
        const scope = this.scope();
        if (scope !== "self") {
            this.store.dispatch(BrandingActions.loadAdminBranding({tenantId: scope}));
        }
    }

    summary(key: McpKey): KeySummary {
        return keySummary(
            key,
            {areas: this.areas(), software: this.connections(), tasks: this.tasks()},
            this.choices(),
        );
    }

    openCreate(): void {
        this.openDialog(null, {
            action: request => McpKeyActions.createMcpKey({scope: this.scope(), request}),
            success: [McpKeyActions.createMcpKeySuccess],
            failure: McpKeyActions.createMcpKeyFailure,
        });
    }

    openEdit(key: McpKey): void {
        this.openDialog(key, {
            action: request => McpKeyActions.updateMcpKey({scope: this.scope(), keyId: key.id, request}),
            success: [McpKeyActions.updateMcpKeySuccess],
            failure: McpKeyActions.updateMcpKeyFailure,
        });
    }

    /** A connection that does not pass through reaches the key's client only through the assistant's answers. */
    softwareLabel(connection: McpConnection): string {
        return connection.passesThrough
            ? connection.name
            : this.translate("mcpKey.softwareAnswersOnly", {name: connection.name});
    }

    command(key: string): string {
        return claudeCodeCommand(this.origin, key, this.brand());
    }

    connector(key: string): ClaudeConnector {
        return claudeConnector(this.origin, key, this.brand());
    }

    /** copiedKey: the key of the snackbar's text once copied ("MCP key copied"). */
    copy(text: string, copiedKey: string): void {
        this.snackBar.open(
            this.translate(this.clipboard.copy(text) ? copiedKey : "mcpKey.snack.copyFailed"),
            this.translate("common.ok"),
            {duration: 3000},
        );
    }

    dismiss(): void {
        this.store.dispatch(McpKeyActions.dismissCreatedMcpKey());
    }

    confirmRenew(key: McpKey): void {
        this.confirm(
            this.translate("mcpKey.confirmRegenerate", {name: key.name}),
            this.translate("mcpKey.regenerate"),
            () => this.store.dispatch(McpKeyActions.renewMcpKey({scope: this.scope(), keyId: key.id})),
        );
    }

    confirmDelete(key: McpKey): void {
        this.confirm(this.translate("mcpKey.confirmRemove", {name: key.name}), this.translate("mcpKey.remove"), () =>
            this.store.dispatch(McpKeyActions.deleteMcpKey({scope: this.scope(), keyId: key.id})),
        );
    }

    private openDialog(key: McpKey | null, spec: DialogSaveSpec<KeyRequest>): void {
        this.dialogSave.open<KeyDialogComponent, KeyDialogData, KeyRequest>(
            KeyDialogComponent,
            {
                width: "560px",
                data: {
                    kind: "mcp",
                    key,
                    choices: this.choices(),
                    areas: this.areas(),
                    software: this.connections().map(connection => ({
                        id: connection.id,
                        label: this.softwareLabel(connection),
                        changeable: changeable(connection),
                    })),
                    tasks: this.tasks(),
                },
            },
            spec,
        );
    }

    private confirm(message: string, confirmLabel: string, action: () => void): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {message, confirmLabel, confirmColor: "warn"},
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(action);
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.translate("common.ok"), {
                duration: isError ? 10000 : 3000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }

    private translate(key: string, params?: Record<string, unknown>): string {
        return this.transloco.translate(key, params);
    }
}
