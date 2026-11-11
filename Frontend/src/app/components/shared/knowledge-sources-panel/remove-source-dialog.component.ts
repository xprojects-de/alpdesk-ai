import {Component, inject} from "@angular/core";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {TranslocoPipe} from "@jsverse/transloco";

/** What removing a source does with its documents: "delete" them, or "keep" them as ordinary ones. */
export type SourceRemoval = "delete" | "keep";

/** Asks whether a removed source's documents go with it or stay - closed with the choice, or nothing. */
@Component({
    selector: "app-remove-source-dialog",
    imports: [MatDialogModule, MatButtonModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ "knowledgeSources.remove.title" | transloco: {name} }}</h2>
        <mat-dialog-content>
            <p class="message">{{ "knowledgeSources.remove.message" | transloco }}</p>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="close()">{{ "common.cancel" | transloco }}</button>
            <button mat-stroked-button (click)="close('keep')">{{ "knowledgeSources.remove.keep" | transloco }}</button>
            <button mat-flat-button class="danger" (click)="close('delete')">
                {{ "knowledgeSources.remove.delete" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .message {
                white-space: pre-line;
                max-width: 520px;
            }

            .danger {
                --mat-button-filled-container-color: var(--app-err);
                --mat-button-filled-label-text-color: var(--app-surface);
            }
        `,
    ],
})
export class RemoveSourceDialogComponent {
    private dialogRef = inject<MatDialogRef<RemoveSourceDialogComponent, SourceRemoval>>(MatDialogRef);
    readonly name = inject<string>(MAT_DIALOG_DATA);

    close(removal?: SourceRemoval): void {
        this.dialogRef.close(removal);
    }
}
