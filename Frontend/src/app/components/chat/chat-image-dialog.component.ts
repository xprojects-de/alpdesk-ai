import {Component, inject} from "@angular/core";
import {MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {TranslocoPipe} from "@jsverse/transloco";

export interface ChatImageDialogData {
    /** The object URL the preview loaded. */
    url: string;
    fileName: string;
}

/** An image the model created, large; closes with true when it is to be downloaded. */
@Component({
    selector: "app-chat-image-dialog",
    imports: [MatDialogModule, MatButtonModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ data.fileName }}</h2>
        <mat-dialog-content>
            <img [src]="data.url" [alt]="data.fileName" />
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="false">{{ "common.close" | transloco }}</button>
            <button mat-flat-button [mat-dialog-close]="true">{{ "common.download" | transloco }}</button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            img {
                display: block;
                max-width: 100%;
                max-height: 75vh;
                margin: 0 auto;
                object-fit: contain;
            }
        `,
    ],
})
export class ChatImageDialogComponent {
    readonly data = inject<ChatImageDialogData>(MAT_DIALOG_DATA);
}
