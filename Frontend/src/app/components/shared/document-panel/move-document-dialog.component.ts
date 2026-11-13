import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule} from "@angular/forms";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatSelectModule} from "@angular/material/select";
import {TranslocoPipe} from "@jsverse/transloco";
import {KnowledgeArea} from "../../../models/knowledge-area.model";
import {dialogSave} from "../dialog-save";

export interface MoveDocumentDialogData {
    path: string;
    areaId: number | null;
    areas: KnowledgeArea[];
}

/** Moves a document to another area or to the company knowledge - who finds it changes at once. */
@Component({
    selector: "app-move-document-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatSelectModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>{{ "documents.panel.move" | transloco }}</h2>
        <mat-dialog-content>
            <p class="path">{{ data.path }}</p>
            <form id="move-document-form" [formGroup]="form" (ngSubmit)="onSave()">
                <!-- The company knowledge is the empty choice (null) - shown as the placeholder, the label above it. -->
                <mat-form-field appearance="outline" [floatLabel]="labelAbove">
                    <mat-label>{{ "documents.moveDialog.area" | transloco }}</mat-label>
                    <mat-select formControlName="areaId" [placeholder]="companyKnowledge | transloco">
                        <mat-option [value]="null">{{ companyKnowledge | transloco }}</mat-option>
                        @for (area of data.areas; track area.id) {
                            <mat-option [value]="area.id">{{ area.name }}</mat-option>
                        }
                    </mat-select>
                    <mat-hint>{{ "documents.moveDialog.hint" | transloco }}</mat-hint>
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="move-document-form" [disabled]="saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .path {
                color: var(--app-text-2);
                word-break: break-all;
            }

            mat-form-field {
                width: 100%;
                min-width: min(420px, 70vw);
            }
        `,
    ],
})
export class MoveDocumentDialogComponent {
    private dialogRef = inject<MatDialogRef<MoveDocumentDialogComponent, number | null>>(MatDialogRef);
    readonly data = inject<MoveDocumentDialogData>(MAT_DIALOG_DATA);
    /** Closes only when moved - on an error (e.g. the same path in that area) the dialog stays open. */
    readonly saver = dialogSave<number | null>();
    readonly companyKnowledge = "knowledgeAreas.companyKnowledgeForAll";
    readonly labelAbove = "always" as const;
    readonly form = inject(FormBuilder).group({areaId: [this.data.areaId]});

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        this.saver.submit(this.form.getRawValue().areaId ?? null);
    }
}
