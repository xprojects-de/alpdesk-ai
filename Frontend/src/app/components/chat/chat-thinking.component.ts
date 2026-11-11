import {Component, computed, input} from "@angular/core";
import {MatIconModule} from "@angular/material/icon";
import {TranslocoPipe} from "@jsverse/transloco";
import {thinkingPreview} from "../../utils/chat-thinking.util";

/**
 * The model's reasoning above its answer, as its provider hands it over (vLLM/Qwen, Claude, Gemini, Ollama - not
 * OpenAI's GPT models): collapsed, while the model thinks with its latest line, afterwards to open. Kept for this
 * session only - it is never stored.
 */
@Component({
    selector: "app-chat-thinking",
    imports: [MatIconModule, TranslocoPipe],
    template: `
        <details class="thinking">
            <summary>
                <mat-icon>psychology</mat-icon>
                @if (live()) {
                    <span class="label">{{ "chat.thinking.live" | transloco }}</span>
                    <span class="preview">{{ preview() }}</span>
                } @else {
                    <span class="label">{{ "chat.thinking.show" | transloco }}</span>
                }
            </summary>
            <div class="text">{{ text() }}</div>
        </details>
    `,
    styles: [
        `
            .thinking {
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

            summary mat-icon {
                font-size: 18px;
                height: 18px;
                width: 18px;
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

            .text {
                white-space: pre-wrap;
                max-height: 320px;
                overflow-y: auto;
                margin-top: 6px;
                padding: 8px 10px;
                border-left: 3px solid var(--app-border-strong);
                background: var(--app-surface-2);
                border-radius: 0 8px 8px 0;
            }
        `,
    ],
})
export class ChatThinkingComponent {
    readonly text = input.required<string>();
    /** The model is still thinking - no answer text yet. */
    readonly live = input(false);

    readonly preview = computed(() => thinkingPreview(this.text()));
}
