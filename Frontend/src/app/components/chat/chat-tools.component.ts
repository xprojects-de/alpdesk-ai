import {Component, computed, input} from "@angular/core";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressSpinnerModule} from "@angular/material/progress-spinner";
import {TranslocoPipe} from "@jsverse/transloco";
import {ToolStep} from "../../models/chat.model";
import {distinctToolViews, toolStepView} from "../../utils/chat-tools.util";

/**
 * The tools the model called for an answer, above it: collapsed, while a tool is at work with that tool, afterwards how
 * many steps and which kinds - to open for each step. Kept for this session only - it is
 * never stored.
 */
@Component({
    selector: "app-chat-tools",
    imports: [MatIconModule, MatProgressSpinnerModule, TranslocoPipe],
    template: `
        <details class="tools">
            <summary>
                @if (running() && current(); as step) {
                    <mat-progress-spinner mode="indeterminate" diameter="14" />
                    <span class="label">{{ step.key | transloco: step.params }}</span>
                } @else {
                    <mat-icon>build</mat-icon>
                    <span class="label">
                        {{
                            (steps().length === 1 ? "chat.tools.summaryOne" : "chat.tools.summaryMany")
                                | transloco: {count: steps().length}
                        }}
                    </span>
                    <span class="preview">
                        @for (view of kinds(); track $index) {
                            @if (!$first) {
                                ·
                            }
                            {{ view.key | transloco: view.params }}
                        }
                    </span>
                }
            </summary>
            <ol class="steps">
                @for (step of shown(); track $index) {
                    <li>
                        <mat-icon>{{ step.icon }}</mat-icon>
                        <span class="label">{{ step.key | transloco: step.params }}</span>
                    </li>
                }
            </ol>
        </details>
    `,
    styles: [
        `
            .tools {
                max-width: 760px;
                margin-bottom: 6px;
                color: var(--app-text-2);
                font-size: 13px;
            }

            summary {
                display: flex;
                align-items: center;
                gap: 6px;
                cursor: pointer;
                list-style: none;
            }

            summary::-webkit-details-marker {
                display: none;
            }

            mat-icon {
                flex: none;
                font-size: 18px;
                height: 18px;
                width: 18px;
            }

            mat-progress-spinner {
                flex: none;
                margin: 0 2px;
            }

            .label {
                font-weight: 500;
                white-space: nowrap;
            }

            .preview {
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
                font-style: italic;
            }

            .steps {
                list-style: none;
                margin: 6px 0 0;
                padding: 8px 10px;
                border-left: 3px solid var(--app-border-strong);
                background: var(--app-surface-2);
                border-radius: 0 8px 8px 0;
            }

            .steps li {
                display: flex;
                align-items: center;
                gap: 6px;
                min-width: 0;
            }

            .steps li + li {
                margin-top: 4px;
            }
        `,
    ],
})
export class ChatToolsComponent {
    readonly steps = input.required<ToolStep[]>();
    /** The last tool is still at work. */
    readonly running = input(false);

    readonly shown = computed(() => this.steps().map(toolStepView));
    readonly current = computed(() => this.shown().at(-1));
    readonly kinds = computed(() => distinctToolViews(this.steps()));
}
