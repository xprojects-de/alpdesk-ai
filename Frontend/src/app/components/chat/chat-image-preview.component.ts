import {Component, computed, effect, inject, input, output} from "@angular/core";
import {MatDialog} from "@angular/material/dialog";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {ChatFile} from "../../models/chat.model";
import * as ChatActions from "../../store/chat/chat.actions";
import * as ChatSelectors from "../../store/chat/chat.selectors";
import {ChatFileChipComponent} from "./chat-file-chip.component";
import {ChatImageDialogComponent, ChatImageDialogData} from "./chat-image-dialog.component";

/**
 * An image the model created, shown below its answer: loaded through HttpClient (the auth interceptor applies) as an
 * object URL - the answer's Markdown still loads nothing by itself. A click shows it large; the chip below downloads it.
 */
@Component({
    selector: "app-chat-image-preview",
    imports: [ChatFileChipComponent, TranslocoPipe],
    template: `
        @if (url(); as url) {
            <button
                type="button"
                class="preview"
                (click)="openLarge(url)"
                [attr.aria-label]="'chat.image.showLarge' | transloco: {name: file().fileName}"
            >
                <img [src]="url" [alt]="file().fileName" />
            </button>
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

            .preview {
                display: block;
                padding: 0;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                background: var(--app-surface-2);
                overflow: hidden;
                cursor: zoom-in;
                max-width: min(360px, 100%);
            }

            img {
                display: block;
                max-width: 100%;
                max-height: 360px;
            }
        `,
    ],
})
export class ChatImagePreviewComponent {
    private store = inject(Store);
    private dialog = inject(MatDialog);

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

    openLarge(url: string): void {
        this.dialog
            .open<ChatImageDialogComponent, ChatImageDialogData, boolean>(ChatImageDialogComponent, {
                data: {url, fileName: this.file().fileName},
                maxWidth: "95vw",
                autoFocus: false,
            })
            .afterClosed()
            .subscribe(download => {
                if (download) {
                    this.download.emit();
                }
            });
    }
}
