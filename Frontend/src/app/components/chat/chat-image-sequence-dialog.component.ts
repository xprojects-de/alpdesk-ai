import {Component, forwardRef, inject} from "@angular/core";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {ChatFile} from "../../models/chat.model";
import * as ChatActions from "../../store/chat/chat.actions";
import {ChatImageSequenceComponent} from "./chat-image-sequence.component";

export interface ChatImageSequenceDialogData {
    files: ChatFile[];
    /** The image to start at. */
    index: number;
}

/** A sequence of images large, with the same controls as below the answer; closes with the image shown last. */
@Component({
    selector: "app-chat-image-sequence-dialog",
    // The sequence opens this dialog and this dialog shows the sequence: resolved when used, not when loaded.
    imports: [forwardRef(() => ChatImageSequenceComponent), MatButtonModule, MatDialogModule, TranslocoPipe],
    template: `
        <mat-dialog-content>
            <app-chat-image-sequence
                [files]="data.files"
                [large]="true"
                [start]="data.index"
                (indexChange)="index = $event"
                (download)="download($event)"
            />
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="close()">{{ "common.close" | transloco }}</button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            mat-dialog-content {
                max-height: none;
                height: calc(100% - 64px);
            }
        `,
    ],
})
export class ChatImageSequenceDialogComponent {
    readonly data = inject<ChatImageSequenceDialogData>(MAT_DIALOG_DATA);
    private readonly dialogRef = inject(MatDialogRef<ChatImageSequenceDialogComponent, number>);
    private readonly store = inject(Store);

    index = this.data.index;

    download(file: ChatFile): void {
        this.store.dispatch(ChatActions.downloadFile({file}));
    }

    close(): void {
        this.dialogRef.close(this.index);
    }
}
