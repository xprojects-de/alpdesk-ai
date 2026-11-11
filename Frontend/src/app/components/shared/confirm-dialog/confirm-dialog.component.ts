import {Component, inject} from "@angular/core";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {TranslocoPipe} from "@jsverse/transloco";

export interface ConfirmDialogData {
    title?: string;
    message: string;
    confirmLabel?: string;
    cancelLabel?: string;
    confirmColor?: "primary" | "warn";
    // Renders a single button (confirmLabel, default "OK") instead of confirm/cancel - for
    // replacing plain informational alert()s that don't gate an action.
    hideCancel?: boolean;
}

@Component({
    selector: "app-confirm-dialog",
    imports: [MatDialogModule, MatButtonModule, TranslocoPipe],
    template: `
        @if (data.title) {
            <h2 mat-dialog-title>{{ data.title }}</h2>
        }
        <mat-dialog-content>
            <p class="confirm-dialog-message">{{ data.message }}</p>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (!data.hideCancel) {
                <button mat-button (click)="onCancel()">{{ data.cancelLabel || ("common.cancel" | transloco) }}</button>
            }
            <button mat-flat-button [class.danger]="data.confirmColor === 'warn'" (click)="onConfirm()">
                {{ data.confirmLabel || ("common.ok" | transloco) }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .confirm-dialog-message {
                white-space: pre-line;
                min-width: 300px;
                max-width: 500px;
            }

            /* A destructive confirmation in the error colour instead of the brand colour. */
            .danger {
                --mat-button-filled-container-color: var(--app-err);
                --mat-button-filled-label-text-color: var(--app-surface);
            }
        `,
    ],
})
export class ConfirmDialogComponent {
    private dialogRef = inject(MatDialogRef<ConfirmDialogComponent>);
    public data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);

    onCancel(): void {
        this.dialogRef.close(false);
    }

    onConfirm(): void {
        this.dialogRef.close(true);
    }
}
