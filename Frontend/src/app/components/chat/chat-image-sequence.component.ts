import {Component, computed, effect, inject, input, linkedSignal, output, signal} from "@angular/core";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog} from "@angular/material/dialog";
import {MatIconModule} from "@angular/material/icon";
import {MatSliderModule} from "@angular/material/slider";
import {MatTooltipModule} from "@angular/material/tooltip";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {ChatFile} from "../../models/chat.model";
import * as ChatActions from "../../store/chat/chat.actions";
import * as ChatSelectors from "../../store/chat/chat.selectors";
import {keyBelongsToTarget, nextWhilePlaying, SEQUENCE_SPEEDS, stepInSequence} from "../../utils/chat-image.util";
import {ChatFileChipComponent} from "./chat-file-chip.component";
import {ChatImageSequenceDialogComponent, ChatImageSequenceDialogData} from "./chat-image-sequence-dialog.component";

/**
 * The images one answer created, shown as a sequence (e.g. the stills of a movement with what was measured drawn in):
 * one image at a time with a slider through all of them, step by step or played like a short clip. Loaded like a
 * single preview - through HttpClient as object URLs the chat slice keeps and releases.
 */
@Component({
    selector: "app-chat-image-sequence",
    imports: [ChatFileChipComponent, MatButtonModule, MatIconModule, MatSliderModule, MatTooltipModule, TranslocoPipe],
    host: {
        tabindex: "0",
        "[class.large]": "large()",
        "(keydown.arrowleft)": "onArrow($event, -1)",
        "(keydown.arrowright)": "onArrow($event, 1)",
        "(keydown.space)": "onSpace($event)",
    },
    template: `
        <button
            type="button"
            class="frame"
            [disabled]="!url() || large()"
            (click)="openLarge()"
            [attr.aria-label]="'chat.sequence.showLarge' | transloco"
        >
            @if (url(); as url) {
                <img [src]="url" [alt]="current().fileName" />
            } @else {
                <span class="loading">{{ "chat.sequence.loading" | transloco }}</span>
            }
        </button>
        <div class="controls">
            <button
                mat-icon-button
                type="button"
                (click)="step(-1)"
                [disabled]="index() === 0"
                [matTooltip]="'chat.sequence.previous' | transloco"
            >
                <mat-icon>skip_previous</mat-icon>
            </button>
            <button
                mat-icon-button
                type="button"
                (click)="togglePlay()"
                [matTooltip]="(playing() ? 'chat.sequence.pause' : 'chat.sequence.play') | transloco"
            >
                <mat-icon>{{ playing() ? "pause" : "play_arrow" }}</mat-icon>
            </button>
            <button
                mat-icon-button
                type="button"
                (click)="step(1)"
                [disabled]="index() === files().length - 1"
                [matTooltip]="'chat.sequence.next' | transloco"
            >
                <mat-icon>skip_next</mat-icon>
            </button>
            <mat-slider class="slider" [min]="0" [max]="files().length - 1" [step]="1">
                <input
                    matSliderThumb
                    [value]="index()"
                    (valueChange)="goTo($event)"
                    [attr.aria-label]="'chat.sequence.slider' | transloco"
                />
            </mat-slider>
            <span class="counter num">{{
                "chat.sequence.position" | transloco: {index: index() + 1, count: files().length}
            }}</span>
            <button
                mat-button
                type="button"
                class="speed"
                (click)="nextSpeed()"
                [matTooltip]="'chat.sequence.speedHint' | transloco"
            >
                {{ "chat.sequence.speed" | transloco: {count: speed()} }}
            </button>
            @if (!large()) {
                <button
                    mat-icon-button
                    type="button"
                    (click)="openLarge()"
                    [disabled]="!url()"
                    [matTooltip]="'chat.sequence.showLarge' | transloco"
                >
                    <mat-icon>open_in_full</mat-icon>
                </button>
            }
        </div>
        <app-chat-file-chip [file]="current()" (download)="download.emit(current())" />
    `,
    styles: [
        `
            :host {
                display: flex;
                flex-direction: column;
                gap: 6px;
                width: min(760px, 100%);
                outline: none;
            }

            :host(.large) {
                width: 100%;
            }

            :host(:focus-visible) .frame {
                outline: 2px solid var(--app-brand);
            }

            .frame {
                display: flex;
                align-items: center;
                justify-content: center;
                padding: 0;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                background: var(--app-surface-2);
                overflow: hidden;
                cursor: zoom-in;
                min-height: 200px;
            }

            .frame:disabled {
                cursor: default;
            }

            img {
                display: block;
                max-width: 100%;
                max-height: 520px;
            }

            :host(.large) .frame {
                cursor: default;
                border: 0;
            }

            :host(.large) img {
                max-height: calc(100vh - 220px);
            }

            .loading {
                color: var(--app-text-3);
                font-size: 13px;
            }

            .controls {
                display: flex;
                align-items: center;
                gap: 2px;
                flex-wrap: wrap;
            }

            .slider {
                flex: 1 1 160px;
                min-width: 120px;
            }

            .counter {
                color: var(--app-text-2);
                font-size: 13px;
                white-space: nowrap;
                margin: 0 6px;
            }

            .speed {
                min-width: 0;
            }
        `,
    ],
})
export class ChatImageSequenceComponent {
    private store = inject(Store);
    private dialog = inject(MatDialog);

    readonly files = input.required<ChatFile[]>();
    /** In the large view (the dialog): as large as the window allows, no zoom of its own. */
    readonly large = input(false);
    /** The image to show first - the large view opens at the one shown below the answer. */
    readonly start = input(0);
    readonly download = output<ChatFile>();
    readonly indexChange = output<number>();

    readonly index = linkedSignal(() => this.start());
    readonly playing = signal(false);
    readonly speed = signal(SEQUENCE_SPEEDS[0]);

    private readonly previews = this.store.selectSignal(ChatSelectors.selectImagePreviews);
    readonly current = computed(() => this.files()[Math.min(this.index(), this.files().length - 1)]);
    readonly url = computed(() => this.previews()[this.current().id] ?? null);

    constructor() {
        // Every image of the sequence loaded at once - playing must not wait for each; null in the store means
        // loading or failed, neither asks again.
        effect(() => {
            const loaded = this.previews();
            for (const file of this.files()) {
                if (!(file.id in loaded)) {
                    this.store.dispatch(ChatActions.loadImagePreview({file}));
                }
            }
        });
        effect(() => this.indexChange.emit(this.index()));
        effect(onCleanup => {
            if (!this.playing()) {
                return;
            }
            const timer = setInterval(
                () => this.index.update(index => nextWhilePlaying(index, this.files().length)),
                1000 / this.speed(),
            );
            onCleanup(() => clearInterval(timer));
        });
    }

    /** The arrow keys step - unless the slider has the focus: it steps itself and reports through goTo. */
    onArrow(event: Event, step: number): void {
        if (keyBelongsToTarget(event, "input")) {
            return;
        }
        event.preventDefault();
        this.step(step);
    }

    /** Space plays and pauses - unless a button or the slider has the focus: Space is its own key there. */
    onSpace(event: Event): void {
        if (keyBelongsToTarget(event, "button, input")) {
            return;
        }
        event.preventDefault();
        this.togglePlay();
    }

    step(step: number): void {
        this.playing.set(false);
        this.index.update(index => stepInSequence(index, step, this.files().length));
    }

    goTo(index: number): void {
        this.playing.set(false);
        this.index.set(index);
    }

    togglePlay(): void {
        this.playing.update(playing => !playing);
    }

    nextSpeed(): void {
        this.speed.update(speed => SEQUENCE_SPEEDS[(SEQUENCE_SPEEDS.indexOf(speed) + 1) % SEQUENCE_SPEEDS.length]);
    }

    /** The whole sequence large, at the image shown now; back here at the one shown last there. */
    openLarge(): void {
        if (!this.url() || this.large()) {
            return;
        }
        this.playing.set(false);
        this.dialog
            .open<ChatImageSequenceDialogComponent, ChatImageSequenceDialogData, number>(
                ChatImageSequenceDialogComponent,
                {
                    data: {files: this.files(), index: this.index()},
                    width: "96vw",
                    maxWidth: "96vw",
                    height: "96vh",
                    autoFocus: "dialog",
                },
            )
            .afterClosed()
            .subscribe(index => {
                if (index !== undefined) {
                    this.index.set(index);
                }
            });
    }
}
