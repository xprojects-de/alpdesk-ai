import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {TenantChoice} from "../../models/auth.model";
import * as AuthSelectors from "../../store/auth/auth.selectors";
import {dialogSave} from "../shared/dialog-save";

/**
 * The switch to the person's user in another tenant without WAM: a username proves no person there, so the target
 * user's password is asked - a login into the other tenant from the menu (docs/architecture/tenancy.md).
 */
@Component({
    selector: "app-switch-tenant-dialog",
    imports: [
        TranslocoPipe,
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatIconModule,
    ],
    template: `
        <h2 mat-dialog-title>{{ "shell.switchTenant.title" | transloco: {tenant: tenant.name} }}</h2>
        <mat-dialog-content>
            <form id="switch-tenant-form" [formGroup]="form" class="switch-form" (ngSubmit)="onSave()">
                <p class="hint">{{ "shell.switchTenant.hint" | transloco }}</p>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "shell.switchTenant.password" | transloco }}</mat-label>
                    <input
                        matInput
                        type="password"
                        formControlName="password"
                        required
                        autocomplete="current-password"
                        cdkFocusInitial
                    />
                </mat-form-field>
                @if (error(); as error) {
                    <p class="error"><mat-icon>error</mat-icon>{{ error }}</p>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="switch-tenant-form" [disabled]="form.invalid || saver.saving()">
                {{ "shell.switchTenant.submit" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .switch-form {
                min-width: min(420px, 70vw);
            }

            mat-form-field {
                width: 100%;
            }

            .hint {
                color: var(--app-text-2);
                margin: 0 0 12px;
            }

            .error {
                display: flex;
                align-items: center;
                gap: 6px;
                color: var(--mat-sys-error);
                margin: 0;
            }
        `,
    ],
})
export class SwitchTenantDialogComponent {
    private fb = inject(FormBuilder);
    private store = inject(Store);
    private dialogRef = inject<MatDialogRef<SwitchTenantDialogComponent, string>>(MatDialogRef);
    readonly saver = dialogSave<string>();
    readonly tenant = inject<TenantChoice>(MAT_DIALOG_DATA);
    readonly error = this.store.selectSignal(AuthSelectors.selectSwitchError);

    form = this.fb.nonNullable.group({password: ["", Validators.required]});

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.valid) {
            this.saver.submit(this.form.getRawValue().password);
        }
    }
}
