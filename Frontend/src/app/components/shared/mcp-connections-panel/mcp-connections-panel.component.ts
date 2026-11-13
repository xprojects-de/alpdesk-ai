import {Component, DestroyRef, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
import {filter} from "rxjs/operators";
import {ActivatedRoute, Router} from "@angular/router";
import {MatButtonModule} from "@angular/material/button";
import {MatCheckboxChange, MatCheckboxModule} from "@angular/material/checkbox";
import {MatDialog} from "@angular/material/dialog";
import {MatExpansionModule} from "@angular/material/expansion";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSlideToggleChange, MatSlideToggleModule} from "@angular/material/slide-toggle";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MCP_ACCESS_LABELS, McpConnection, McpConnectionRequest, McpPrompt, McpTool} from "../../../models/mcp.model";
import {TenantScope} from "../../../models/tenant.model";
import * as McpConnectionActions from "../../../store/mcp-connection/mcp-connection.actions";
import * as McpConnectionSelectors from "../../../store/mcp-connection/mcp-connection.selectors";
import {signInCallback, signInChip, signInReturnUrl, toolState} from "../../../utils/mcp-connection.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {DialogSave} from "../dialog-save";
import {McpConnectionDialogComponent} from "./mcp-connection-dialog.component";
import * as AuthSelectors from "../../../store/auth/auth.selectors";

/**
 * The tenant's software, connected through its MCP server: the tools it offers - each off until turned on here - and
 * per user what it may do with it. The tenant admin's page "Software" and the admin's dialog of the same name.
 */
@Component({
    selector: "app-mcp-connections-panel",
    imports: [
        MatButtonModule,
        MatCheckboxModule,
        MatExpansionModule,
        MatFormFieldModule,
        MatIconModule,
        MatProgressBarModule,
        MatSlideToggleModule,
        MatTooltipModule,
        EmptyStateComponent,
        TranslocoPipe,
        TranslocoDatePipe,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "mcpConnections.panel.title" | transloco }}</span>
                <span class="muted">{{ "mcpConnections.panel.intro" | transloco }}</span>
                <span class="spacer"></span>
                <button mat-flat-button (click)="openCreate()">
                    <mat-icon>add_link</mat-icon>
                    {{ "mcpConnections.panel.connect" | transloco }}
                </button>
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (connections().length === 0) {
                @if (!loading()) {
                    <app-empty-state
                        icon="hub"
                        [title]="'mcpConnections.panel.emptyTitle' | transloco"
                        [text]="'mcpConnections.panel.emptyText' | transloco"
                    >
                        <button mat-flat-button (click)="openCreate()">
                            <mat-icon>add_link</mat-icon>
                            {{ "mcpConnections.panel.connect" | transloco }}
                        </button>
                    </app-empty-state>
                }
            } @else {
                <p class="app-note">
                    <mat-icon>info</mat-icon>
                    <span>{{ "mcpConnections.panel.note" | transloco }}</span>
                </p>

                <mat-accordion multi>
                    @for (connection of connections(); track connection.id) {
                        <mat-expansion-panel (opened)="onOpened(connection)">
                            <mat-expansion-panel-header>
                                <mat-panel-title>{{ connection.name }}</mat-panel-title>
                                <mat-panel-description>
                                    @if (connection.toolsError) {
                                        <span class="app-chip err">{{
                                            "mcpConnections.panel.toolsUnreadable" | transloco
                                        }}</span>
                                    } @else if (connection.protocol === "A2A") {
                                        <span class="app-tag">{{ "software.protocol.a2a" | transloco }}</span>
                                        @if (connection.answersChat) {
                                            <span class="app-tag">{{ "software.answersChatTag" | transloco }}</span>
                                        }
                                        <span class="app-chip ok">{{
                                            "software.skillCount" | transloco: {count: connection.tools.length}
                                        }}</span>
                                    } @else {
                                        <span class="app-chip" [class.ok]="enabledCount(connection) > 0">
                                            {{
                                                "mcpConnections.panel.toolsEnabled"
                                                    | transloco
                                                        : {
                                                              enabled: enabledCount(connection),
                                                              total: connection.tools.length,
                                                          }
                                            }}
                                        </span>
                                    }
                                    @if (connection.signIn; as signIn) {
                                        <span
                                            class="app-chip"
                                            [class.ok]="chip(signIn).tone === 'ok'"
                                            [class.warn]="chip(signIn).tone === 'warn'"
                                            [class.info]="chip(signIn).tone === 'info'"
                                            >{{ chip(signIn).label | transloco }}</span
                                        >
                                    }
                                </mat-panel-description>
                            </mat-expansion-panel-header>

                            <div class="meta">
                                <span class="mono">{{ connection.baseUrl }}</span>
                                @if (connection.signIn; as signIn) {
                                    <span>
                                        {{ "mcpConnections.signIn.through" | transloco: {issuer: signIn.issuer} }}
                                        @if (signIn.scopesGranted.length > 0) {
                                            {{
                                                "mcpConnections.signIn.scopes"
                                                    | transloco: {scopes: signIn.scopesGranted.join(", ")}
                                            }}
                                        }
                                    </span>
                                } @else {
                                    <span>
                                        {{ "mcpConnections.panel.key" | transloco }}
                                        <span class="mono">{{
                                            connection.keyHint ?? ("mcpConnections.panel.keyNone" | transloco)
                                        }}</span>
                                    </span>
                                }
                                <span>
                                    {{
                                        (connection.protocol === "A2A"
                                            ? "software.cardLoaded"
                                            : "mcpConnections.panel.toolsLoaded"
                                        ) | transloco
                                    }}
                                    {{
                                        connection.toolsLoadedAt
                                            ? (connection.toolsLoadedAt
                                              | translocoDate: {dateStyle: "medium", timeStyle: "short"})
                                            : ("mcpConnections.panel.toolsLoadedNever" | transloco)
                                    }}
                                </span>
                            </div>
                            @if (connection.toolsError) {
                                <p class="error"><mat-icon>warning</mat-icon>{{ connection.toolsError }}</p>
                            }
                            @if (connection.signIn?.lastError) {
                                <p class="error"><mat-icon>warning</mat-icon>{{ connection.signIn?.lastError }}</p>
                            }
                            @if (connection.promptsError) {
                                <p class="error">
                                    <mat-icon>warning</mat-icon
                                    >{{
                                        "mcpConnections.panel.promptsUnreadable"
                                            | transloco: {error: connection.promptsError}
                                    }}
                                </p>
                            }
                            <div class="actions">
                                @if (connection.signIn; as signIn) {
                                    <button mat-stroked-button (click)="signInTo(connection)">
                                        <mat-icon>login</mat-icon>
                                        {{
                                            (signIn.status === "SIGNED_IN"
                                                ? "mcpConnections.signIn.again"
                                                : "mcpConnections.signIn.signIn"
                                            ) | transloco
                                        }}
                                    </button>
                                }
                                <button
                                    mat-stroked-button
                                    (click)="reload(connection)"
                                    [disabled]="reloadingId() === connection.id"
                                >
                                    <mat-icon>refresh</mat-icon>
                                    {{
                                        (connection.protocol === "A2A"
                                            ? "software.reload"
                                            : "mcpConnections.panel.reload"
                                        ) | transloco
                                    }}
                                </button>
                                @if (connection.protocol === "A2A") {
                                    <button
                                        mat-stroked-button
                                        (click)="check(connection)"
                                        [disabled]="checkingId() === connection.id"
                                    >
                                        <mat-icon>network_check</mat-icon>
                                        {{ "software.check" | transloco }}
                                    </button>
                                }
                                <button mat-button (click)="openEdit(connection)">
                                    <mat-icon>edit</mat-icon>
                                    {{ "common.edit" | transloco }}
                                </button>
                                @if (connection.signIn) {
                                    <button mat-button (click)="removeSignIn(connection)">
                                        <mat-icon>logout</mat-icon>
                                        {{ "mcpConnections.signIn.remove" | transloco }}
                                    </button>
                                }
                                <button mat-button class="danger" (click)="confirmDelete(connection)">
                                    <mat-icon>delete</mat-icon>
                                    {{ "common.remove" | transloco }}
                                </button>
                            </div>
                            @if (checks()[connection.id]; as result) {
                                @if (result.ok) {
                                    <p class="app-note">
                                        <mat-icon>check_circle</mat-icon>
                                        {{
                                            "software.checkOk"
                                                | transloco
                                                    : {name: result.name, skills: result.skills, millis: result.millis}
                                        }}
                                    </p>
                                } @else {
                                    <p class="error"><mat-icon>warning</mat-icon>{{ result.error }}</p>
                                }
                            }
                            @if (reloadingId() === connection.id) {
                                <mat-progress-bar mode="indeterminate" />
                            }

                            @if (connection.protocol === "A2A") {
                                <h3>{{ "software.skills" | transloco }}</h3>
                                <p class="tool-description">{{ "software.skillsHint" | transloco }}</p>
                                @if (connection.tools.length === 0) {
                                    <p class="muted">{{ "software.noSkills" | transloco }}</p>
                                }
                                @for (skill of connection.tools; track skill.id) {
                                    <div class="skill">
                                        <div class="tool-name">{{ skill.name }}</div>
                                        <div class="tool-description">{{ skill.description }}</div>
                                    </div>
                                }
                            } @else {
                                <h3>{{ "mcpConnections.tools.title" | transloco }}</h3>
                                @if (connection.tools.length === 0) {
                                    <p class="muted">{{ "mcpConnections.tools.none" | transloco }}</p>
                                } @else {
                                    <table class="grid">
                                        <thead>
                                            <tr>
                                                <th>{{ "mcpConnections.tools.on" | transloco }}</th>
                                                <th>{{ "mcpConnections.tools.tool" | transloco }}</th>
                                                <th [matTooltip]="'mcpConnections.tools.changesTooltip' | transloco">
                                                    {{ "mcpConnections.tools.changes" | transloco }}
                                                </th>
                                                <th>{{ "mcpConnections.tools.inChat" | transloco }}</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            @for (tool of connection.tools; track tool.id) {
                                                <tr [class.gone]="!tool.offered">
                                                    <td>
                                                        <mat-slide-toggle
                                                            [checked]="tool.enabled"
                                                            [disabled]="!tool.offered"
                                                            (change)="switchTool(connection, tool, 'enabled', $event)"
                                                        />
                                                    </td>
                                                    <td>
                                                        <div class="tool-name">{{ tool.name }}</div>
                                                        <div class="tool-description">{{ tool.description }}</div>
                                                    </td>
                                                    <td>
                                                        <mat-checkbox
                                                            [checked]="tool.changes"
                                                            (change)="switchTool(connection, tool, 'changes', $event)"
                                                        />
                                                    </td>
                                                    <td class="state">{{ state(tool) | transloco }}</td>
                                                </tr>
                                            }
                                        </tbody>
                                    </table>
                                }
                            }
                            @if (connection.prompts.length > 0) {
                                <h3>{{ "mcpConnections.prompts.title" | transloco }}</h3>
                                <p class="muted">{{ "mcpConnections.prompts.text" | transloco }}</p>
                                <table class="grid">
                                    <thead>
                                        <tr>
                                            <th>{{ "mcpConnections.tools.on" | transloco }}</th>
                                            <th>{{ "mcpConnections.prompts.prompt" | transloco }}</th>
                                            <th>{{ "mcpConnections.prompts.arguments" | transloco }}</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        @for (prompt of connection.prompts; track prompt.id) {
                                            <tr [class.gone]="!prompt.offered">
                                                <td>
                                                    <mat-slide-toggle
                                                        [checked]="prompt.enabled"
                                                        [disabled]="!prompt.offered"
                                                        (change)="switchPrompt(connection, prompt, $event)"
                                                    />
                                                </td>
                                                <td>
                                                    <div class="tool-name">{{ prompt.title || prompt.name }}</div>
                                                    <div class="tool-description">{{ prompt.description }}</div>
                                                    @if (!prompt.offered) {
                                                        <div class="tool-description">
                                                            {{ "mcpConnections.toolState.gone" | transloco }}
                                                        </div>
                                                    }
                                                </td>
                                                <td class="tool-description">
                                                    {{
                                                        argumentNames(prompt) ||
                                                            ("mcpConnections.prompts.noArguments" | transloco)
                                                    }}
                                                </td>
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                            }

                            @if (connection.instructions) {
                                <h3>{{ "mcpConnections.instructions.title" | transloco }}</h3>
                                <p class="tool-description">{{ "mcpConnections.instructions.hint" | transloco }}</p>
                                <pre class="instructions">{{ connection.instructions }}</pre>
                                @if (connection.instructions.length >= maxInstructions) {
                                    <p class="tool-description">
                                        {{ "mcpConnections.instructions.cut" | transloco: {max: maxInstructions} }}
                                    </p>
                                }
                            }

                            <h3>{{ "mcpConnections.access.title" | transloco }}</h3>
                            <p class="tool-description">{{ "mcpConnections.access.groupsHint" | transloco }}</p>
                            @if (access()[connection.id]; as groups) {
                                <table class="grid">
                                    <tbody>
                                        @if (connection.protocol !== "A2A") {
                                            <tr>
                                                <td>
                                                    <div>{{ "mcpConnections.access.passThrough" | transloco }}</div>
                                                    <div class="tool-description">
                                                        {{ "mcpConnections.access.passThroughText" | transloco }}
                                                    </div>
                                                </td>
                                                <td class="level">
                                                    <mat-slide-toggle
                                                        [checked]="connection.passesThrough"
                                                        (change)="setPassThrough(connection, $event)"
                                                        [attr.aria-label]="
                                                            'mcpConnections.access.passThrough' | transloco
                                                        "
                                                    />
                                                </td>
                                            </tr>
                                            <tr>
                                                <td>
                                                    <div>{{ "mcpConnections.access.passResources" | transloco }}</div>
                                                    <div class="tool-description">
                                                        {{ "mcpConnections.access.passResourcesText" | transloco }}
                                                    </div>
                                                </td>
                                                <td class="level">
                                                    <mat-slide-toggle
                                                        [checked]="connection.passesResources"
                                                        [disabled]="!connection.passesThrough"
                                                        (change)="setPassResources(connection, $event)"
                                                        [attr.aria-label]="
                                                            'mcpConnections.access.passResources' | transloco
                                                        "
                                                    />
                                                </td>
                                            </tr>
                                        }
                                        @for (group of groups; track group.groupId) {
                                            <tr>
                                                <td>
                                                    <div>{{ group.name }}</div>
                                                    <!-- Unchecked, a group with a WAM group counts for nobody - said here too. -->
                                                    <div
                                                        class="tool-description"
                                                        [class.unchecked]="group.wamGroup && !wamGroupsChecked()"
                                                    >
                                                        @if (!group.wamGroup) {
                                                            {{ "mcpConnections.access.groupText" | transloco }}
                                                        } @else if (wamGroupsChecked()) {
                                                            {{
                                                                "groups.wamGroupOf"
                                                                    | transloco: {wamGroup: group.wamGroup}
                                                            }}
                                                        } @else {
                                                            {{
                                                                "groups.wamGroupUnchecked"
                                                                    | transloco: {wamGroup: group.wamGroup}
                                                            }}
                                                        }
                                                    </div>
                                                </td>
                                                <td class="level">
                                                    @if (group.level) {
                                                        <span class="app-tag">{{
                                                            levelLabels[group.level] | transloco
                                                        }}</span>
                                                    } @else {
                                                        <span class="muted">{{
                                                            "mcpConnections.access.none" | transloco
                                                        }}</span>
                                                    }
                                                </td>
                                            </tr>
                                        } @empty {
                                            <tr>
                                                <td class="muted">
                                                    {{ "mcpConnections.access.noGroups" | transloco }}
                                                </td>
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                            } @else {
                                <mat-progress-bar mode="indeterminate" />
                            }
                        </mat-expansion-panel>
                    }
                </mat-accordion>
            }
        </div>
    `,
    styles: [
        `
            .app-note {
                margin: 12px 14px;
            }

            /* Flat rows inside the panel instead of cards of their own. */
            mat-accordion {
                display: block;
                --mat-expansion-container-elevation-shadow: none;
                --mat-expansion-container-shape: 0;
                --mat-expansion-container-background-color: transparent;
            }

            mat-expansion-panel {
                border-top: 1px solid var(--app-border);
            }

            .actions {
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                margin: 8px 0 12px;
            }

            .meta {
                display: flex;
                flex-wrap: wrap;
                gap: 16px;
                color: var(--app-text-2);
                font-size: 13px;
                word-break: break-all;
            }

            .error {
                display: flex;
                align-items: center;
                gap: 8px;
                color: var(--app-err);
            }

            h3 {
                font-size: 14px;
                font-weight: 600;
                margin: 16px 0 4px;
            }

            .grid {
                width: 100%;
                border-collapse: collapse;
            }

            .grid th {
                text-align: left;
                font-weight: 600;
                font-size: 12px;
                color: var(--app-text-3);
                padding: 4px 8px;
            }

            .grid td {
                padding: 6px 8px;
                border-top: 1px solid var(--app-border);
                vertical-align: middle;
            }

            .tool-name {
                font-family: var(--app-mono);
                font-size: 12.5px;
            }

            .tool-description {
                color: var(--app-text-3);
                font-size: 12.5px;
            }

            .instructions {
                white-space: pre-wrap;
                word-break: break-word;
                max-height: 240px;
                overflow-y: auto;
                margin: 4px 0 8px;
                padding: 8px 10px;
                background: var(--app-surface-2);
                border: 1px solid var(--app-border);
                border-radius: 6px;
                font-family: var(--app-mono);
                font-size: 12px;
            }

            .tool-description.unchecked {
                color: var(--app-warn);
            }

            .state {
                font-size: 13px;
                color: var(--app-text-2);
            }

            .gone {
                opacity: 0.5;
            }

            .level {
                width: 220px;
            }

            .level mat-form-field {
                width: 100%;
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class McpConnectionsPanelComponent implements OnInit {
    /** The backend keeps a software's instructions up to this length (McpConnectionService.MAX_INSTRUCTIONS). */
    readonly maxInstructions = 2000;

    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);

    readonly scope = input.required<TenantScope>();

    readonly connections = this.store.selectSignal(McpConnectionSelectors.selectConnections);
    readonly loading = this.store.selectSignal(McpConnectionSelectors.selectConnectionsLoading);
    readonly reloadingId = this.store.selectSignal(McpConnectionSelectors.selectReloadingId);
    readonly checks = this.store.selectSignal(McpConnectionSelectors.selectChecks);
    readonly checkingId = this.store.selectSignal(McpConnectionSelectors.selectCheckingId);
    readonly access = this.store.selectSignal(McpConnectionSelectors.selectAccess);
    readonly wamGroupsChecked = this.store.selectSignal(AuthSelectors.selectWamGroupsChecked);
    readonly levelLabels = MCP_ACCESS_LABELS;
    readonly state = toolState;
    readonly chip = signInChip;

    constructor() {
        this.notifyOn(McpConnectionActions.createConnectionSuccess, ({connection}) =>
            this.toolsReadMessage(connection, "created"),
        );
        this.notifyOn(McpConnectionActions.reloadToolsSuccess, ({connection}) =>
            this.toolsReadMessage(connection, "reloaded"),
        );
        this.notifyOn(McpConnectionActions.completeSignInSuccess, ({connection}) =>
            this.transloco.translate("mcpConnections.signIn.done", {
                name: connection.name,
                count: connection.tools.length,
            }),
        );
        this.notifyOn(McpConnectionActions.deleteConnectionSuccess, () =>
            this.transloco.translate("mcpConnections.snack.deleted"),
        );
        for (const failure of [
            McpConnectionActions.createConnectionFailure,
            McpConnectionActions.updateConnectionFailure,
            McpConnectionActions.deleteConnectionFailure,
            McpConnectionActions.reloadToolsFailure,
            McpConnectionActions.checkConnectionFailure,
            McpConnectionActions.updateToolFailure,
            McpConnectionActions.updatePromptFailure,
            McpConnectionActions.setPassThroughFailure,
            McpConnectionActions.setPassResourcesFailure,
            McpConnectionActions.signInFailure,
            McpConnectionActions.completeSignInFailure,
            McpConnectionActions.removeSignInFailure,
        ]) {
            this.notifyOn(failure, ({error}) => this.transloco.translate("mcpConnections.snack.error", {error}), true);
        }
    }

    ngOnInit(): void {
        this.store.dispatch(McpConnectionActions.loadConnections({scope: this.scope()}));
        this.completeSignIn();
    }

    /** To the server's sign-in page in this tab - it sends the browser back to this page. */
    signInTo(connection: McpConnection): void {
        this.store.dispatch(
            McpConnectionActions.signIn({
                scope: this.scope(),
                connectionId: connection.id,
                returnUrl: signInReturnUrl(window.location),
            }),
        );
    }

    removeSignIn(connection: McpConnection): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("mcpConnections.signIn.confirmRemove", {name: connection.name}),
                    confirmLabel: this.transloco.translate("common.remove"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() =>
                this.store.dispatch(
                    McpConnectionActions.removeSignIn({scope: this.scope(), connectionId: connection.id}),
                ),
            );
    }

    /**
     * Back from the server's sign-in page: the address carries the server's answer, which this page completes - as the
     * one signed in here, who must be the one who started it. Taken out of the address at once, so a reload does not
     * send it again. The connections are read again once it is done (the effect).
     */
    private completeSignIn(): void {
        const back = signInCallback(window.location.search);
        if (!back) {
            return;
        }
        this.store.dispatch(McpConnectionActions.completeSignIn({scope: this.scope(), ...back}));
        void this.router.navigate([], {relativeTo: this.route, queryParams: {}, replaceUrl: true});
    }

    enabledCount(connection: McpConnection): number {
        return connection.tools.filter(tool => tool.enabled && tool.offered).length;
    }

    onOpened(connection: McpConnection): void {
        this.store.dispatch(McpConnectionActions.loadAccess({scope: this.scope(), connectionId: connection.id}));
    }

    openCreate(): void {
        this.dialogSave.open<McpConnectionDialogComponent, McpConnection | null, McpConnectionRequest>(
            McpConnectionDialogComponent,
            {data: null},
            {
                action: request => McpConnectionActions.createConnection({scope: this.scope(), request}),
                success: [McpConnectionActions.createConnectionSuccess],
                failure: McpConnectionActions.createConnectionFailure,
            },
        );
    }

    openEdit(connection: McpConnection): void {
        this.dialogSave.open<McpConnectionDialogComponent, McpConnection | null, McpConnectionRequest>(
            McpConnectionDialogComponent,
            {data: connection},
            {
                action: request =>
                    McpConnectionActions.updateConnection({scope: this.scope(), connectionId: connection.id, request}),
                success: [McpConnectionActions.updateConnectionSuccess],
                failure: McpConnectionActions.updateConnectionFailure,
            },
        );
    }

    check(connection: McpConnection): void {
        this.store.dispatch(McpConnectionActions.checkConnection({scope: this.scope(), connectionId: connection.id}));
    }

    reload(connection: McpConnection): void {
        this.store.dispatch(McpConnectionActions.reloadTools({scope: this.scope(), connectionId: connection.id}));
    }

    /**
     * Every switch and level shows the stored value until the backend has changed it - a refused change leaves it
     * as it was (like the API keys' switches: the reloaded list repeats the stored value, which Angular would not
     * write into the control again).
     */
    switchTool(
        connection: McpConnection,
        tool: McpTool,
        field: "enabled" | "changes",
        change: MatSlideToggleChange | MatCheckboxChange,
    ): void {
        change.source.checked = tool[field];
        this.store.dispatch(
            McpConnectionActions.updateTool({
                scope: this.scope(),
                connectionId: connection.id,
                toolId: tool.id,
                change: {[field]: change.checked},
            }),
        );
    }

    switchPrompt(connection: McpConnection, prompt: McpPrompt, change: MatSlideToggleChange): void {
        change.source.checked = prompt.enabled;
        this.store.dispatch(
            McpConnectionActions.updatePrompt({
                scope: this.scope(),
                connectionId: connection.id,
                promptId: prompt.id,
                enabled: change.checked,
            }),
        );
    }

    /** The prompt's arguments as the user meets them in the chat's form - required ones marked with "*". */
    argumentNames(prompt: McpPrompt): string {
        return prompt.arguments.map(argument => argument.name + (argument.required ? "*" : "")).join(", ");
    }

    /** The switch shows the stored value until the backend has changed it - a refused change leaves it as it was. */
    setPassThrough(connection: McpConnection, change: MatSlideToggleChange): void {
        change.source.checked = connection.passesThrough;
        this.store.dispatch(
            McpConnectionActions.setPassThrough({
                scope: this.scope(),
                connectionId: connection.id,
                passesThrough: change.checked,
            }),
        );
    }

    /** As the pass-through switch: the stored value until the backend has changed it. */
    setPassResources(connection: McpConnection, change: MatSlideToggleChange): void {
        change.source.checked = connection.passesResources;
        this.store.dispatch(
            McpConnectionActions.setPassResources({
                scope: this.scope(),
                connectionId: connection.id,
                passesResources: change.checked,
            }),
        );
    }

    confirmDelete(connection: McpConnection): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("mcpConnections.confirmDelete", {name: connection.name}),
                    confirmLabel: this.transloco.translate("common.remove"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() =>
                this.store.dispatch(
                    McpConnectionActions.deleteConnection({scope: this.scope(), connectionId: connection.id}),
                ),
            );
    }

    /** An agent's skills are read from its card and on at once - a software's tools start off. */
    private toolsReadMessage(connection: McpConnection, event: "created" | "reloaded"): string {
        const texts = connection.protocol === "A2A" ? "software.snack" : "mcpConnections.snack";
        if (connection.toolsError) {
            return this.transloco.translate(`${texts}.${event}Unreadable`, {error: connection.toolsError});
        }
        const count = connection.tools.length;
        return this.transloco.translate(`${texts}.${event}.${count === 1 ? "one" : "other"}`, {count});
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
