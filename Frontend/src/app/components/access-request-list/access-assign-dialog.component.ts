import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatSelectModule} from "@angular/material/select";
import {MatButtonModule} from "@angular/material/button";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {AccessAssignment, AccessRequest} from "../../models/access-request.model";
import * as TenantActions from "../../store/tenant/tenant.actions";
import * as TenantSelectors from "../../store/tenant/tenant.selectors";
import {DEFAULT_LANGUAGE, LANGUAGES, languageOf} from "../../utils/language.util";
import {dialogSave} from "../shared/dialog-save";

/**
 * Assigns an access request to a tenant: the person becomes its user, without a password. Approved at once - e.g. a
 * tenant's first user, who as its tenant admin approves the others - or left for the tenant admin to approve.
 */
@Component({
    selector: "app-access-assign-dialog",
    imports: [
        TranslocoPipe,
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatSelectModule,
        MatButtonModule,
        MatSlideToggleModule,
    ],
    template: `
        <h2 mat-dialog-title>{{ "accessRequests.assign.title" | transloco: {name: request.displayName} }}</h2>
        <mat-dialog-content>
            <form id="access-assign-form" [formGroup]="form" class="assign-form" (ngSubmit)="onSave()">
                <p class="hint">{{ "accessRequests.assign.username" | transloco: {username: request.username} }}</p>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "accessRequests.assign.tenant" | transloco }}</mat-label>
                    <mat-select formControlName="tenantId" required>
                        @for (tenant of tenants(); track tenant.id) {
                            <mat-option [value]="tenant.id">{{ tenant.name }}</mat-option>
                        }
                    </mat-select>
                    @if (form.controls.tenantId.hasError("required") && form.controls.tenantId.touched) {
                        <mat-error>{{ "accessRequests.assign.tenantRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "accessRequests.assign.language" | transloco }}</mat-label>
                    <mat-select formControlName="language">
                        @for (language of languages; track language.value) {
                            <mat-option [value]="language.value">{{ language.label }}</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
                <mat-slide-toggle formControlName="active">
                    {{ "accessRequests.assign.active" | transloco }}
                </mat-slide-toggle>
                <mat-slide-toggle formControlName="admin">
                    {{ "accessRequests.assign.admin" | transloco }}
                </mat-slide-toggle>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="access-assign-form" [disabled]="form.invalid || saver.saving()">
                {{ "accessRequests.assign.submit" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .assign-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(420px, 70vw);
            }

            mat-form-field {
                width: 100%;
            }

            mat-slide-toggle {
                margin: 4px 0 8px;
            }

            .hint {
                color: var(--app-text-2);
                margin: 0 0 8px;
            }
        `,
    ],
})
export class AccessAssignDialogComponent {
    private fb = inject(FormBuilder);
    private store = inject(Store);
    private dialogRef = inject<MatDialogRef<AccessAssignDialogComponent, AccessAssignment>>(MatDialogRef);
    readonly saver = dialogSave<AccessAssignment>();
    readonly request = inject<AccessRequest>(MAT_DIALOG_DATA);
    readonly tenants = this.store.selectSignal(TenantSelectors.selectAllTenants);
    readonly languages = LANGUAGES;

    form = this.fb.group({
        tenantId: this.fb.control<number | null>(null, Validators.required),
        active: this.fb.nonNullable.control(false),
        admin: this.fb.nonNullable.control(false),
        // The person's browser language, as it signed in - else the default.
        language: this.fb.nonNullable.control<string>(languageOf(this.request.language ?? DEFAULT_LANGUAGE)),
    });

    constructor() {
        this.store.dispatch(TenantActions.loadTenants());
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        const {tenantId, active, admin, language} = this.form.getRawValue();
        if (this.form.invalid || tenantId === null) {
            return;
        }
        this.saver.submit({tenantId, active, admin, language});
    }
}
