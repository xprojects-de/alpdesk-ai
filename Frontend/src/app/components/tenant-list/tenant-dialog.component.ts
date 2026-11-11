import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatButtonModule} from "@angular/material/button";
import {MatSelectModule} from "@angular/material/select";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {SEARCH_LANGUAGES, SearchLanguage, Tenant, TenantRequest} from "../../models/tenant.model";
import {notBlank} from "../../utils/validators.util";
import {
    buildTenantRequest,
    MAX_SANDBOX_TOKEN,
    MIN_SANDBOX_TOKEN,
    sandboxComplete,
    sandboxToken,
} from "../../utils/tenant.util";
import {dialogSave} from "../shared/dialog-save";
import {TranslocoPipe} from "@jsverse/transloco";

/** Create/edit dialog: opened with a Tenant to edit, or null to create. Closes with a TenantRequest or nothing. */
@Component({
    selector: "app-tenant-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatSelectModule,
        MatSlideToggleModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>{{ (data ? "tenants.dialog.editTitle" : "tenants.dialog.createTitle") | transloco }}</h2>
        <mat-dialog-content>
            <form id="tenant-form" [formGroup]="form" class="tenant-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input matInput formControlName="name" required cdkFocusInitial />
                    @if (form.controls.name.hasError("required") && form.controls.name.touched) {
                        <mat-error>{{ "tenants.dialog.nameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.name.hasError("blank")) {
                        <mat-error>{{ "tenants.dialog.nameBlank" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "tenants.dialog.contactEmail" | transloco }}</mat-label>
                    <input matInput type="email" formControlName="contactEmail" />
                    @if (form.controls.contactEmail.hasError("email")) {
                        <mat-error>{{ "tenants.dialog.emailInvalid" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "tenants.dialog.notes" | transloco }}</mat-label>
                    <textarea matInput formControlName="notes" rows="3"></textarea>
                </mat-form-field>
                @if (!data) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "documents.searchLanguage.label" | transloco }}</mat-label>
                        <mat-select formControlName="searchLanguage">
                            @for (choice of languages; track choice) {
                                <mat-option [value]="choice">{{ "searchLanguages." + choice | transloco }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "tenants.dialog.searchLanguageHint" | transloco }}</mat-hint>
                    </mat-form-field>
                }
                <mat-slide-toggle formControlName="active">{{ "tenants.dialog.active" | transloco }}</mat-slide-toggle>
                <mat-slide-toggle formControlName="globalKeysAllowed">
                    {{ "tenants.dialog.globalKeysAllowed" | transloco }}
                </mat-slide-toggle>
                <mat-slide-toggle formControlName="tasksAllowed">
                    {{ "tenants.dialog.tasksAllowed" | transloco }}
                </mat-slide-toggle>
                <mat-slide-toggle formControlName="a2aAllowed">
                    {{ "tenants.dialog.a2aAllowed" | transloco }}
                </mat-slide-toggle>
                <p class="section-note">{{ "tenants.dialog.a2aAllowedHint" | transloco }}</p>

                <div class="section">{{ "tenants.dialog.codeExecution" | transloco }}</div>
                <p class="section-note">
                    {{ "tenants.dialog.codeExecutionNote" | transloco }}
                </p>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "tenants.dialog.sandboxUrls" | transloco }}</mat-label>
                    <textarea
                        matInput
                        formControlName="sandboxUrls"
                        rows="2"
                        [placeholder]="'tenants.dialog.sandboxUrlsPlaceholder' | transloco"
                    ></textarea>
                    <mat-hint>{{ "tenants.dialog.sandboxUrlsHint" | transloco }}</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "tenants.dialog.sandboxToken" | transloco }}</mat-label>
                    <input
                        matInput
                        type="password"
                        formControlName="sandboxToken"
                        autocomplete="new-password"
                        [placeholder]="
                            data?.sandboxTokenHint
                                ? ('tenants.dialog.tokenStored' | transloco: {hint: data?.sandboxTokenHint})
                                : ''
                        "
                    />
                    <mat-hint>
                        {{
                            (data?.sandboxTokenHint ? "tenants.dialog.tokenHintStored" : "tenants.dialog.tokenHint")
                                | transloco: {min: minToken, max: maxToken}
                        }}
                    </mat-hint>
                    @if (form.controls.sandboxToken.hasError("sandboxToken")) {
                        <mat-error>{{
                            "tenants.dialog.tokenInvalid" | transloco: {min: minToken, max: maxToken}
                        }}</mat-error>
                    }
                </mat-form-field>
                @if (form.hasError("sandboxTokenMissing")) {
                    <div class="form-error">{{ "tenants.dialog.tokenMissing" | transloco }}</div>
                }
                @if (form.hasError("sandboxAddressesMissing")) {
                    <div class="form-error">{{ "tenants.dialog.addressesMissing" | transloco }}</div>
                }
                @if (data) {
                    <div class="database">{{ "tenants.dialog.database" | transloco: {name: data.databaseName} }}</div>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <!-- Submit button of the form above (via form=) so Enter in a field saves too. -->
            <button mat-flat-button type="submit" form="tenant-form" [disabled]="form.invalid || saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .tenant-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(420px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            mat-slide-toggle {
                margin: 4px 0 8px;
            }

            .database {
                font-size: 12px;
                color: var(--app-text-2);
            }

            .section {
                font-weight: 600;
                margin-top: 8px;
            }

            .section-note {
                margin: 0 0 4px;
                font-size: 12.5px;
                color: var(--app-text-2);
            }

            .form-error {
                font-size: 12px;
                color: var(--mat-sys-error);
            }
        `,
    ],
})
export class TenantDialogComponent {
    private fb = inject(FormBuilder);
    private dialogRef = inject<MatDialogRef<TenantDialogComponent, TenantRequest>>(MatDialogRef);
    /** Saves and closes only when saved - on an error the dialog stays open with the input. */
    readonly saver = dialogSave<TenantRequest>();
    public data = inject<Tenant | null>(MAT_DIALOG_DATA);

    readonly minToken = MIN_SANDBOX_TOKEN;
    readonly maxToken = MAX_SANDBOX_TOKEN;
    readonly languages = SEARCH_LANGUAGES;

    form = this.fb.nonNullable.group(
        {
            name: [this.data?.name ?? "", [Validators.required, notBlank()]],
            contactEmail: [this.data?.contactEmail ?? "", [Validators.email]],
            notes: [this.data?.notes ?? ""],
            active: [this.data?.active ?? true],
            globalKeysAllowed: [this.data?.globalKeysAllowed ?? false],
            tasksAllowed: [this.data?.tasksAllowed ?? false],
            a2aAllowed: [this.data?.a2aAllowed ?? false],
            sandboxUrls: [(this.data?.sandboxUrls ?? "").split(",").join("\n")],
            sandboxToken: ["", [sandboxToken()]],
            // On create only; a change has its own place on the tab "Firmenwissen".
            searchLanguage: [(this.data?.searchLanguage ?? "SIMPLE") as SearchLanguage],
        },
        {validators: sandboxComplete(!!this.data?.sandboxTokenHint)},
    );

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildTenantRequest(this.form.getRawValue(), this.data));
    }
}
