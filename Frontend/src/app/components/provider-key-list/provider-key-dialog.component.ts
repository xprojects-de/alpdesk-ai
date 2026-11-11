import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatButtonModule} from "@angular/material/button";
import {TranslocoPipe} from "@jsverse/transloco";
import {AiProvider, PROVIDER_LABELS} from "../../models/ai.model";
import {notBlank} from "../../utils/validators.util";
import {dialogSave} from "../shared/dialog-save";

export interface ProviderKeyDialogData {
    provider: AiProvider;
    /** A key is stored and gets replaced. */
    replacing: boolean;
}

/** Sets or replaces the API key of one provider; closes with the trimmed key. */
@Component({
    selector: "app-provider-key-dialog",
    imports: [TranslocoPipe, ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule],
    template: `
        @let provider = providerLabels[data.provider] | transloco;
        <h2 mat-dialog-title>
            {{
                (data.replacing ? "providerKeys.dialog.replaceTitle" : "providerKeys.dialog.setTitle")
                    | transloco: {provider}
            }}
        </h2>
        <mat-dialog-content>
            <form id="provider-key-form" [formGroup]="form" class="key-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "providerKeys.dialog.apiKey" | transloco }}</mat-label>
                    <input
                        matInput
                        type="password"
                        formControlName="apiKey"
                        required
                        autocomplete="off"
                        cdkFocusInitial
                    />
                    <mat-hint>{{ "providerKeys.dialog.hint" | transloco }}</mat-hint>
                    @if (form.controls.apiKey.hasError("required") && form.controls.apiKey.touched) {
                        <mat-error>{{ "providerKeys.dialog.required" | transloco }}</mat-error>
                    }
                    @if (form.controls.apiKey.hasError("blank")) {
                        <mat-error>{{ "providerKeys.dialog.blank" | transloco }}</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="provider-key-form" [disabled]="form.invalid || saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .key-form {
                min-width: min(420px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }
        `,
    ],
})
export class ProviderKeyDialogComponent {
    private fb = inject(FormBuilder);
    private dialogRef = inject<MatDialogRef<ProviderKeyDialogComponent, string>>(MatDialogRef);
    readonly saver = dialogSave<string>();
    readonly data = inject<ProviderKeyDialogData>(MAT_DIALOG_DATA);
    readonly providerLabels = PROVIDER_LABELS;

    form = this.fb.nonNullable.group({apiKey: ["", [Validators.required, notBlank()]]});

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(this.form.getRawValue().apiKey.trim());
    }
}
