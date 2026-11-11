import {Component, computed, effect, inject, input, output} from "@angular/core";
import {Store} from "@ngrx/store";
import {ChatFile} from "../../models/chat.model";
import * as ChatActions from "../../store/chat/chat.actions";
import * as ChatSelectors from "../../store/chat/chat.selectors";
import {ChatFileChipComponent} from "./chat-file-chip.component";

/**
 * A clip the model handed out (e.g. cut out of a video with marks drawn in), played below its answer with the
 * browser's own controls: loaded like an image preview - through HttpClient as an object URL the chat slice keeps
 * and releases -, so the answer's Markdown still loads nothing by itself. The chip below downloads it.
 */
@Component({
    selector: "app-chat-video-preview",
    imports: [ChatFileChipComponent],
    template: `
        @if (url(); as url) {
            <video [src]="url" controls preload="metadata" playsinline [attr.aria-label]="file().fileName"></video>
        }
        <app-chat-file-chip [file]="file()" (download)="download.emit()" />
    `,
    styles: [
        `
            :host {
                display: inline-flex;
                flex-direction: column;
                align-items: flex-start;
                gap: 6px;
                max-width: 100%;
            }

            video {
                display: block;
                width: min(560px, 100%);
                max-height: 420px;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                background: var(--app-surface-2);
            }
        `,
    ],
})
export class ChatVideoPreviewComponent {
    private store = inject(Store);

    readonly file = input.required<ChatFile>();
    readonly download = output();

    private readonly previews = this.store.selectSignal(ChatSelectors.selectImagePreviews);
    readonly url = computed(() => this.previews()[this.file().id] ?? null);

    constructor() {
        // Loaded once per conversation: null in the store means loading or failed - neither asks again.
        effect(() => {
            if (!(this.file().id in this.previews())) {
                this.store.dispatch(ChatActions.loadImagePreview({file: this.file()}));
            }
        });
    }
}
