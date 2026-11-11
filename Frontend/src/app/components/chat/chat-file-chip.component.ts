import {Component, computed, input, output} from "@angular/core";
import {MatIconModule} from "@angular/material/icon";
import {MatTooltipModule} from "@angular/material/tooltip";
import {TranslocoPipe} from "@jsverse/transloco";
import {ChatFile} from "../../models/chat.model";
import {chatFileIcon} from "../../utils/chat-file.util";
import {formatBytes} from "../../utils/document.util";
import {injectLocale} from "../../i18n/locale";

/** A conversation file as a chip: click downloads it; with `removable` a small x asks to remove it. */
@Component({
    selector: "app-chat-file-chip",
    imports: [MatIconModule, MatTooltipModule, TranslocoPipe],
    template: `
        @if (file().expired) {
            <span class="name expired" [matTooltip]="'chat.file.expired' | transloco">
                <mat-icon class="type">timer_off</mat-icon>
                <span class="label">{{ file().fileName }}</span>
                <span class="size">{{ "chat.file.expiredLabel" | transloco }}</span>
            </span>
        } @else {
            <button
                type="button"
                class="name"
                (click)="download.emit()"
                [matTooltip]="'chat.file.download' | transloco: {name: file().fileName}"
            >
                <mat-icon class="type">{{ icon() }}</mat-icon>
                <span class="label">{{ file().fileName }}</span>
                <span class="size">{{ size() }}</span>
                @if (file().origin === "GENERATED") {
                    <mat-icon class="generated" [matTooltip]="'chat.file.generated' | transloco">auto_awesome</mat-icon>
                }
                <mat-icon class="action">download</mat-icon>
            </button>
        }
        @if (removable()) {
            <button type="button" class="remove" (click)="remove.emit()" [matTooltip]="'chat.file.remove' | transloco">
                <mat-icon>close</mat-icon>
            </button>
        }
    `,
    styles: [
        `
            :host {
                display: inline-flex;
                align-items: center;
                max-width: 100%;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                background: var(--app-surface);
                font-size: 13px;
            }

            .expired {
                display: inline-flex;
                align-items: center;
                gap: 4px;
                padding: 4px 6px 4px 10px;
                min-width: 0;
                color: var(--app-text-3);
            }

            button {
                display: inline-flex;
                align-items: center;
                gap: 4px;
                border: 0;
                background: none;
                cursor: pointer;
                font: inherit;
                color: inherit;
                padding: 4px 6px 4px 10px;
                min-width: 0;
            }

            .label {
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
                max-width: 240px;
            }

            .size {
                color: var(--app-text-3);
                white-space: nowrap;
            }

            mat-icon {
                font-size: 18px;
                width: 18px;
                height: 18px;
                flex: 0 0 auto;
            }

            .generated {
                color: var(--app-brand-text);
            }

            .action,
            .remove mat-icon {
                color: var(--app-text-3);
            }

            .remove {
                padding: 4px 8px 4px 2px;
            }
        `,
    ],
})
export class ChatFileChipComponent {
    readonly file = input.required<ChatFile>();
    readonly removable = input(false);
    readonly download = output();
    readonly remove = output();

    readonly icon = computed(() => chatFileIcon(this.file().contentType, this.file().fileName));
    private readonly locale = injectLocale();
    readonly size = computed(() => formatBytes(this.file().sizeBytes, this.locale()));
}
