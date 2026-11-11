import {Component, inject, signal} from "@angular/core";
import {FormBuilder, ReactiveFormsModule} from "@angular/forms";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatIconModule} from "@angular/material/icon";
import {MatSelectModule} from "@angular/material/select";
import {TranslocoPipe} from "@jsverse/transloco";
import {KnowledgeArea} from "../../../models/knowledge-area.model";
import {AreaText} from "../../../utils/knowledge-area.util";
import {maxZipMb, refusedZip} from "../../../utils/doc-site.util";
import {dialogSave} from "../dialog-save";

export interface DocSiteUploadDialogData {
    areas: KnowledgeArea[];
    /** The scope chosen first - a row's "Ersetzen". */
    areaId: number | null;
    /** The largest ZIP the backend takes; null: not known yet - then only the backend checks. */
    maxZipBytes: number | null;
}

export interface DocSiteUploadValue {
    file: File;
    areaId: number | null;
}

/** Uploads a ZIP with a static website for the whole tenant or an area - it replaces that scope's website. */
@Component({
    selector: "app-doc-site-upload-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatSelectModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>{{ "handbook.upload.title" | transloco }}</h2>
        <mat-dialog-content>
            <p class="hint">
                {{ "handbook.upload.hint" | transloco }}
                @if (data.maxZipBytes !== null) {
                    {{ "handbook.upload.limit" | transloco: {maxMb: maxMb(data.maxZipBytes)} }}
                }
            </p>
            <form id="doc-site-upload-form" [formGroup]="form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "handbook.upload.scope" | transloco }}</mat-label>
                    <mat-select formControlName="areaId">
                        <mat-option [value]="null">{{ "handbook.companyForAll" | transloco }}</mat-option>
                        @for (area of data.areas; track area.id) {
                            <mat-option [value]="area.id">{{ area.name }}</mat-option>
                        }
                    </mat-select>
                    <mat-hint>{{ "handbook.upload.scopeHint" | transloco }}</mat-hint>
                </mat-form-field>
            </form>
            <div class="file">
                <button mat-stroked-button type="button" (click)="input.click()">
                    <mat-icon>folder_zip</mat-icon>
                    {{ "handbook.upload.choose" | transloco }}
                </button>
                <span class="name">{{ file()?.name ?? ("handbook.upload.noFile" | transloco) }}</span>
                <input #input type="file" accept=".zip,application/zip" hidden (change)="onFile(input)" />
            </div>
            @if (refused(); as reason) {
                <p class="error">{{ reason.key | transloco: reason.params }}</p>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="doc-site-upload-form" [disabled]="saver.saving() || !file()">
                {{ (saver.saving() ? "handbook.upload.uploading" : "handbook.upload.submit") | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .hint {
                color: var(--app-text-2);
                font-size: 13px;
            }

            mat-form-field {
                width: 100%;
                min-width: min(420px, 70vw);
            }

            .file {
                display: flex;
                align-items: center;
                gap: 12px;
                margin-top: 12px;
            }

            .name {
                color: var(--app-text-2);
                word-break: break-all;
            }

            .error {
                color: var(--app-err);
                font-size: 13px;
            }
        `,
    ],
})
export class DocSiteUploadDialogComponent {
    private dialogRef = inject<MatDialogRef<DocSiteUploadDialogComponent, DocSiteUploadValue>>(MatDialogRef);
    readonly data = inject<DocSiteUploadDialogData>(MAT_DIALOG_DATA);
    /** Closes only when stored - a ZIP refused (no index.html, too large) keeps the dialog open. */
    readonly saver = dialogSave<DocSiteUploadValue>();
    readonly form = inject(FormBuilder).group({areaId: [this.data.areaId]});
    readonly file = signal<File | null>(null);
    readonly refused = signal<AreaText | null>(null);
    readonly maxMb = maxZipMb;

    onFile(input: HTMLInputElement): void {
        const chosen = input.files?.[0] ?? null;
        input.value = "";
        const reason = chosen === null ? null : refusedZip(chosen, this.data.maxZipBytes);
        this.refused.set(reason);
        this.file.set(reason === null ? chosen : null);
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        const file = this.file();
        if (file) {
            this.saver.submit({file, areaId: this.form.getRawValue().areaId ?? null});
        }
    }
}
