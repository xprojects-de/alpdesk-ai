import {Component, computed, inject, input} from "@angular/core";
import {Clipboard} from "@angular/cdk/clipboard";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {Store} from "@ngrx/store";
import {MatButtonModule} from "@angular/material/button";
import {MatExpansionModule} from "@angular/material/expansion";
import {MatIconModule} from "@angular/material/icon";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import {
    backendOrigin,
    claudeCodeCommand,
    claudeConnector,
    copilotConfig,
    MCP_KEY_PLACEHOLDER,
} from "../../utils/mcp.util";
import {ClaudeConnectorComponent} from "../shared/claude-connector/claude-connector.component";
import {environment} from "../../../environments/environment";

/**
 * How a tenant's people connect Claude Code, GitHub Copilot, claude.ai or Cowork to its MCP server
 * (docs/architecture/mcp.md "Connecting Claude Code"): the command, VS Code's configuration and the connector's settings, named after the tenant's brand - only the key is missing, it
 * comes from a tenant admin (one of the tenant's keys, each with its own rights). Shown on the page "Konto" when the tenant has a key.
 */
@Component({
    selector: "app-mcp-connect",
    imports: [
        TranslocoPipe,
        MatButtonModule,
        MatExpansionModule,
        MatIconModule,
        MatTooltipModule,
        ClaudeConnectorComponent,
    ],
    template: `
        <!-- Closed at first: the page is about the account - this is for those who program. -->
        <mat-expansion-panel class="app-panel">
            <mat-expansion-panel-header>
                <mat-panel-title class="app-panel-title">{{ "account.connect.title" | transloco }}</mat-panel-title>
                <mat-panel-description class="muted">{{ "account.connect.summary" | transloco }}</mat-panel-description>
            </mat-expansion-panel-header>
            <div class="body">
                <p>{{ "account.connect.text" | transloco: {brand: brand()} }}</p>
                <p class="hint">
                    {{
                        (admin() ? "account.connect.keyAdmin" : "account.connect.keyUser")
                            | transloco: {placeholder: placeholder}
                    }}
                </p>

                <div class="label">{{ "account.connect.claudeCode" | transloco }}</div>
                <div class="code-row">
                    <code>{{ command() }}</code>
                    <button
                        mat-icon-button
                        (click)="copy(command(), 'mcpKey.copied.command')"
                        [matTooltip]="'mcpKey.copyCommand' | transloco"
                        [attr.aria-label]="'mcpKey.copyCommand' | transloco"
                    >
                        <mat-icon>content_copy</mat-icon>
                    </button>
                </div>

                <div class="label">
                    {{ "account.connect.copilot" | transloco }} <code class="mono">.vscode/mcp.json</code>
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
                <app-claude-connector [connector]="connector()" />
                <p class="hint">{{ "account.connect.example" | transloco: {brand: brand()} }}</p>
            </div>
        </mat-expansion-panel>
    `,
    styles: [
        `
            :host {
                display: block;
            }

            /* Like the page's other cards: their border and corners, no shadow; the title on one line. */
            mat-expansion-panel {
                box-shadow: none;
            }

            mat-expansion-panel-header {
                padding: 0 14px;
            }

            mat-panel-title {
                flex: 0 0 auto;
            }

            mat-panel-description {
                font-size: 13px;
                min-width: 0;
            }

            p {
                margin: 0 0 12px;
            }

            /* The panel's body sets a larger type than the page's cards have. */
            .body {
                font-size: 14px;
            }

            .body p:last-child {
                margin-bottom: 0;
            }

            .hint {
                color: var(--app-text-2);
                font-size: 13px;
            }

            .label {
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
        `,
    ],
})
export class McpConnectComponent {
    private store = inject(Store);
    private clipboard = inject(Clipboard);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);

    /** A tenant admin creates the key on the page "API-Keys"; everyone else gets it from one. */
    readonly admin = input.required<boolean>();
    readonly placeholder = MCP_KEY_PLACEHOLDER;

    private readonly origin = backendOrigin(environment.apiUrl, window.location.origin);
    private readonly branding = this.store.selectSignal(BrandingSelectors.selectBranding);
    readonly brand = computed(() => this.branding().name);
    readonly command = computed(() => claudeCodeCommand(this.origin, MCP_KEY_PLACEHOLDER, this.brand()));
    readonly copilot = computed(() => copilotConfig(this.origin, this.brand()));
    readonly connector = computed(() => claudeConnector(this.origin, MCP_KEY_PLACEHOLDER, this.brand()));

    copy(text: string, copiedKey: string): void {
        this.snackBar.open(
            this.transloco.translate(this.clipboard.copy(text) ? copiedKey : "mcpKey.snack.copyFailed"),
            this.transloco.translate("common.ok"),
            {duration: 3000},
        );
    }
}
