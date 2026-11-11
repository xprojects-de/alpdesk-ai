import {Component, inject, input} from "@angular/core";
import {Clipboard} from "@angular/cdk/clipboard";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {ClaudeConnector} from "../../../utils/mcp.util";

/**
 * What to enter in the dialog "Add custom connector" of claude.ai, Claude Desktop and Cowork - field by field, in the
 * order the dialog asks (docs/architecture/mcp.md "Connecting claude.ai and Cowork"). The one stumble seen was the
 * header's value without the space after "Bearer", so the value is copied whole. On the MCP key card (with the new key
 * or a placeholder) and on the page "Konto" (with the placeholder).
 */
@Component({
    selector: "app-claude-connector",
    imports: [TranslocoPipe, MatButtonModule, MatIconModule, MatTooltipModule],
    template: `
        <div class="label">{{ "mcpKey.connector.title" | transloco }}</div>
        <p class="hint">{{ "mcpKey.connector.where" | transloco }}</p>
        <dl>
            <dt>{{ "mcpKey.connector.name" | transloco }}</dt>
            <dd>{{ connector().name }}</dd>

            <dt>{{ "mcpKey.connector.url" | transloco }}</dt>
            <dd class="code-row">
                <code>{{ connector().url }}</code>
                <button
                    mat-icon-button
                    (click)="copy(connector().url, 'mcpKey.copied.url')"
                    [matTooltip]="'mcpKey.copyUrl' | transloco"
                    [attr.aria-label]="'mcpKey.copyUrl' | transloco"
                >
                    <mat-icon>content_copy</mat-icon>
                </button>
            </dd>

            <dt>{{ "mcpKey.connector.authentication" | transloco }}</dt>
            <dd>{{ "mcpKey.connector.noSignIn" | transloco }}</dd>

            <dt>{{ "mcpKey.connector.header" | transloco }}</dt>
            <dd>
                <code class="mono">{{ connector().header }}</code>
            </dd>

            <dt>{{ "mcpKey.connector.value" | transloco }}</dt>
            <dd class="code-row">
                <code>{{ connector().value }}</code>
                <button
                    mat-icon-button
                    (click)="copy(connector().value, 'mcpKey.copied.value')"
                    [matTooltip]="'mcpKey.copyValue' | transloco"
                    [attr.aria-label]="'mcpKey.copyValue' | transloco"
                >
                    <mat-icon>content_copy</mat-icon>
                </button>
            </dd>
        </dl>
        <p class="hint">{{ "mcpKey.connector.hint" | transloco }}</p>
    `,
    styles: [
        `
            :host {
                display: block;
            }

            .label {
                font-weight: 500;
                margin: 4px 0;
            }

            .hint {
                color: var(--app-text-2);
                font-size: 13px;
                margin: 0 0 8px;
            }

            dl {
                display: grid;
                grid-template-columns: max-content minmax(0, 1fr);
                align-items: center;
                gap: 4px 12px;
                margin: 0 0 8px;
                font-size: 13px;
            }

            dt {
                color: var(--app-text-2);
            }

            dd {
                margin: 0;
                min-width: 0;
            }

            .code-row {
                display: flex;
                align-items: center;
                gap: 4px;
            }

            code {
                background: var(--app-surface-2);
                border: 1px solid var(--app-border);
                padding: 4px 8px;
                border-radius: 6px;
                font-family: var(--app-mono);
                font-size: 12px;
                word-break: break-all;
            }

            .code-row code {
                flex: 1;
                min-width: 0;
            }

            /* Below 600px the labels stand above their values. */
            @media (max-width: 600px) {
                dl {
                    grid-template-columns: minmax(0, 1fr);
                }

                dt {
                    margin-top: 4px;
                }
            }
        `,
    ],
})
export class ClaudeConnectorComponent {
    private clipboard = inject(Clipboard);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);

    readonly connector = input.required<ClaudeConnector>();

    copy(text: string, copiedKey: string): void {
        this.snackBar.open(
            this.transloco.translate(this.clipboard.copy(text) ? copiedKey : "mcpKey.snack.copyFailed"),
            this.transloco.translate("common.ok"),
            {duration: 3000},
        );
    }
}
