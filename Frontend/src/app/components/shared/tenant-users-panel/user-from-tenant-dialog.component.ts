import {Component, computed, inject} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatSelectModule} from "@angular/material/select";
import {MatButtonModule} from "@angular/material/button";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {UserFromTenantRequest} from "../../../models/tenant-user.model";
import * as AuthSelectors from "../../../store/auth/auth.selectors";
import * as TenantActions from "../../../store/tenant/tenant.actions";
import * as TenantSelectors from "../../../store/tenant/tenant.selectors";
import * as TenantUserActions from "../../../store/tenant-user/tenant-user.actions";
import * as TenantUserSelectors from "../../../store/tenant-user/tenant-user.selectors";
import {dialogSave} from "../dialog-save";

export interface UserFromTenantDialogData {
    /** The tenant the user is added to - not offered as the source. */
    tenantId: number;
}

/**
 * The admin adds a person's user of another tenant to this one (docs/architecture/tenancy.md "Who does what"): the
 * same username, display name and language, no password - the person then switches between the two in its menu (with
 * the WAM proxy at once; without it once the new user has a password of its own, which the dialog says).
 */
@Component({
    selector: "app-user-from-tenant-dialog",
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
        <h2 mat-dialog-title>{{ "users.fromTenant.title" | transloco }}</h2>
        <mat-dialog-content>
            <form id="user-from-tenant-form" [formGroup]="form" class="from-form" (ngSubmit)="onSave()">
                <p class="hint">{{ "users.fromTenant.hint" | transloco }}</p>
                @if (!wamEnabled()) {
                    <p class="app-note">{{ "users.fromTenant.passwordHint" | transloco }}</p>
                }
                <mat-form-field appearance="outline">
                    <mat-label>{{ "users.fromTenant.tenant" | transloco }}</mat-label>
                    <mat-select formControlName="sourceTenantId" required>
                        @for (tenant of sourceTenants(); track tenant.id) {
                            <mat-option [value]="tenant.id">{{ tenant.name }}</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "users.fromTenant.user" | transloco }}</mat-label>
                    <mat-select formControlName="userId" required>
                        @for (user of users(); track user.id) {
                            <mat-option [value]="user.id">{{ user.displayName }} ({{ user.username }})</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
                <mat-slide-toggle formControlName="active">{{
                    "users.fromTenant.active" | transloco
                }}</mat-slide-toggle>
                <mat-slide-toggle formControlName="admin">{{ "users.fromTenant.admin" | transloco }}</mat-slide-toggle>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button
                mat-flat-button
                type="submit"
                form="user-from-tenant-form"
                [disabled]="form.invalid || saver.saving()"
            >
                {{ "users.fromTenant.submit" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .from-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(440px, 70vw);
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
export class UserFromTenantDialogComponent {
    private fb = inject(FormBuilder);
    private store = inject(Store);
    private dialogRef = inject<MatDialogRef<UserFromTenantDialogComponent, UserFromTenantRequest>>(MatDialogRef);
    readonly saver = dialogSave<UserFromTenantRequest>();
    readonly data = inject<UserFromTenantDialogData>(MAT_DIALOG_DATA);
    private readonly tenants = this.store.selectSignal(TenantSelectors.selectAllTenants);
    readonly sourceTenants = computed(() => this.tenants().filter(tenant => tenant.id !== this.data.tenantId));
    readonly users = this.store.selectSignal(TenantUserSelectors.selectOtherTenantUsers);
    /** Without a WAM proxy the new user has no password - nothing to sign in or switch to until one is set. */
    readonly wamEnabled = this.store.selectSignal(AuthSelectors.selectWamEnabled);

    form = this.fb.group({
        sourceTenantId: this.fb.control<number | null>(null, Validators.required),
        userId: this.fb.control<number | null>(null, Validators.required),
        active: this.fb.nonNullable.control(true),
        admin: this.fb.nonNullable.control(false),
    });

    constructor() {
        this.store.dispatch(TenantActions.loadTenants());
        this.form.controls.sourceTenantId.valueChanges.pipe(takeUntilDestroyed()).subscribe(tenantId => {
            this.form.controls.userId.setValue(null);
            if (tenantId !== null) {
                this.store.dispatch(TenantUserActions.loadOtherTenantUsers({tenantId}));
            }
        });
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        const {userId, active, admin} = this.form.getRawValue();
        if (this.form.invalid || userId === null) {
            return;
        }
        this.saver.submit({userId, active, admin});
    }
}
