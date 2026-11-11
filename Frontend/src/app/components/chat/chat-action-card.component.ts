import {Component, computed, input, output} from "@angular/core";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {TranslocoPipe} from "@jsverse/transloco";
import {ActionDecision, PendingAction} from "../../models/chat.model";
import {actionArguments, actionOutcome} from "../../utils/chat-action.util";

/**
 * A changing tool call of the company's software an answer stopped at: what it would do, and - while it waits -
 * run / cancel. Nothing reaches the software before the user runs it.
 */
@Component({
    selector: "app-chat-action-card",
    imports: [MatButtonModule, MatIconModule, TranslocoPipe],
    template: `
        <div class="card" [class.waiting]="action().status === 'PENDING'">
            <div class="title">
                <mat-icon>build</mat-icon>
                <span>{{ "chat.action.title" | transloco: {software: action().software} }}</span>
            </div>
            <div class="tool">{{ action().tool }}</div>
            @if (lines().length) {
                <ul class="arguments">
                    @for (line of lines(); track $index) {
                        <li>{{ line }}</li>
                    }
                </ul>
            }
            @if (outcome(); as text) {
                <div class="outcome">{{ text | transloco }}</div>
            } @else {
                <div class="hint">{{ "chat.action.hint" | transloco: {software: action().software} }}</div>
                <div class="buttons">
                    <button mat-flat-button [disabled]="disabled()" (click)="decide.emit('run')">
                        <mat-icon>play_arrow</mat-icon>
                        {{ "chat.action.run" | transloco }}
                    </button>
                    <button mat-stroked-button [disabled]="disabled()" (click)="decide.emit('cancel')">
                        {{ "chat.action.cancel" | transloco }}
                    </button>
                </div>
            }
        </div>
    `,
    styles: [
        `
            .card {
                border: 1px solid var(--app-border);
                border-radius: 10px;
                padding: 12px 14px;
                margin-top: 8px;
                max-width: 560px;
                background: var(--app-surface);
            }

            .card.waiting {
                border-color: color-mix(in oklab, var(--app-brand) 55%, var(--app-border));
                box-shadow: var(--app-shadow-1);
            }

            .title {
                display: flex;
                align-items: center;
                gap: 6px;
                font-weight: 500;
            }

            .title mat-icon {
                font-size: 18px;
                height: 18px;
                width: 18px;
            }

            .tool {
                font-family: var(--app-mono);
                font-size: 12.5px;
                margin-top: 4px;
            }

            .arguments {
                margin: 4px 0;
                padding-left: 20px;
                font-size: 13px;
                word-break: break-word;
            }

            .hint,
            .outcome {
                color: var(--app-text-2);
                font-size: 13px;
                margin-top: 4px;
            }

            .buttons {
                display: flex;
                gap: 8px;
                margin-top: 8px;
            }
        `,
    ],
})
export class ChatActionCardComponent {
    readonly action = input.required<PendingAction>();
    /** An answer is streaming - nothing else is decided meanwhile. */
    readonly disabled = input(false);
    readonly decide = output<ActionDecision>();

    readonly lines = computed(() => actionArguments(this.action()));
    readonly outcome = computed(() => actionOutcome(this.action()));
}
