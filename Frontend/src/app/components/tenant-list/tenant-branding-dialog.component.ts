import {Component, effect, inject} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators} from "@angular/forms";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatIconModule} from "@angular/material/icon";
import {MatInputModule} from "@angular/material/input";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TenantBrandingRequest} from "../../models/branding.model";
import {Tenant} from "../../models/tenant.model";
import * as BrandingActions from "../../store/branding/branding.actions";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import {buildTenantBrandingRequest, isHexColor} from "../../utils/branding.util";
import {formatBytes} from "../../utils/document.util";
import {dialogSave} from "../shared/dialog-save";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";

/** Like the backend: PNG, JPEG or WebP (never SVG), at most 512 KB. */
const MAX_LOGO_BYTES = 512 * 1024;

function hexColorOrEmpty(control: AbstractControl): ValidationErrors | null {
    const value = (control.value as string).trim();
    return value === "" || isHexColor(value) ? null : {hexColor: true};
}

/**
 * The admin sets one tenant's look in the tenant area: name, primary colour and logo - an empty value means the
 * installation's default. Name and colour are saved with the dialog, the logo at once.
 */
@Component({
    selector: "app-tenant-branding-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressBarModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>{{ "branding.dialog.title" | transloco: {name: tenant.name} }}</h2>
        <mat-dialog-content>
            <p class="hint">{{ "branding.dialog.intro" | transloco }}</p>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            <form id="branding-form" [formGroup]="form" class="branding-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input matInput formControlName="name" [placeholder]="branding()?.defaultName ?? ''" />
                    <mat-hint>{{ "branding.dialog.default" | transloco: {value: branding()?.defaultName} }}</mat-hint>
                    @if (form.controls.name.hasError("maxlength")) {
                        <mat-error>{{ "branding.dialog.nameTooLong" | transloco: {max: maxName} }}</mat-error>
                    }
                </mat-form-field>

                <div class="color-row">
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "branding.dialog.primaryColor" | transloco }}</mat-label>
                        <input
                            matInput
                            formControlName="primaryColor"
                            [placeholder]="branding()?.defaultPrimaryColor ?? ''"
                        />
                        <mat-hint>{{
                            "branding.dialog.colorHint" | transloco: {value: branding()?.defaultPrimaryColor}
                        }}</mat-hint>
                        @if (form.controls.primaryColor.hasError("hexColor")) {
                            <mat-error>{{ "branding.dialog.colorInvalid" | transloco }}</mat-error>
                        }
                    </mat-form-field>
                    <input
                        type="color"
                        class="color-picker"
                        [attr.aria-label]="'branding.dialog.pickColor' | transloco"
                        [value]="shownColor()"
                        (input)="onColorPicked($event)"
                    />
                </div>
            </form>

            <h3>{{ "branding.dialog.logo" | transloco }}</h3>
            <div class="logo-row">
                @if (logoUrl(); as logo) {
                    <img class="logo" [src]="logo" [alt]="'branding.dialog.logo' | transloco" />
                } @else {
                    <span class="hint">{{ "branding.dialog.noLogo" | transloco }}</span>
                }
                <input
                    #fileInput
                    type="file"
                    hidden
                    accept="image/png,image/jpeg,image/webp"
                    (change)="onLogoChosen(fileInput)"
                />
                <button mat-stroked-button type="button" (click)="fileInput.click()" [disabled]="loading()">
                    <mat-icon>upload</mat-icon>
                    {{
                        (branding()?.hasLogo ? "branding.dialog.replaceLogo" : "branding.dialog.uploadLogo") | transloco
                    }}
                </button>
                @if (branding()?.hasLogo) {
                    <button mat-button color="warn" type="button" (click)="onLogoRemoved()" [disabled]="loading()">
                        <mat-icon>delete</mat-icon>
                        {{ "common.remove" | transloco }}
                    </button>
                }
            </div>
            <p class="hint small">{{ "branding.dialog.logoRules" | transloco: {max: maxLogoSize} }}</p>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button
                mat-flat-button
                type="submit"
                form="branding-form"
                [disabled]="form.invalid || saver.saving() || !branding()"
            >
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .hint {
                color: var(--app-text-2);
                margin-top: 8px;
            }

            .small {
                font-size: 12px;
            }

            .branding-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(440px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            .color-row {
                display: flex;
                align-items: flex-start;
                gap: 12px;
            }

            .color-picker {
                width: 48px;
                height: 48px;
                margin-top: 4px;
                border: none;
                background: none;
                cursor: pointer;
            }

            h3 {
                margin: 8px 0;
                font-size: 14px;
                font-weight: 500;
            }

            .logo-row {
                display: flex;
                align-items: center;
                gap: 12px;
                flex-wrap: wrap;
            }

            .logo {
                max-height: 48px;
                max-width: 160px;
                object-fit: contain;
            }
        `,
    ],
})
export class TenantBrandingDialogComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private snackBar = inject(MatSnackBar);
    private fb = inject(FormBuilder);
    private transloco = inject(TranslocoService);
    private dialogRef = inject<MatDialogRef<TenantBrandingDialogComponent, TenantBrandingRequest>>(MatDialogRef);
    /** Saves name and colour and closes only when saved - on an error the dialog stays open with the input. */
    readonly saver = dialogSave<TenantBrandingRequest>();
    readonly tenant = inject<Tenant>(MAT_DIALOG_DATA);
    readonly maxName = 60;
    readonly maxLogoSize = formatBytes(MAX_LOGO_BYTES);

    readonly branding = this.store.selectSignal(BrandingSelectors.selectAdminBranding);
    readonly logoUrl = this.store.selectSignal(BrandingSelectors.selectAdminLogoUrl);
    readonly loading = this.store.selectSignal(BrandingSelectors.selectAdminBrandingLoading);

    form = this.fb.nonNullable.group({
        name: ["", [Validators.maxLength(60)]],
        primaryColor: ["", [hexColorOrEmpty]],
    });

    constructor() {
        this.store.dispatch(BrandingActions.loadAdminBranding({tenantId: this.tenant.id}));

        // The form starts from what is stored - once, so a logo change does not throw away what is being typed.
        let filled = false;
        effect(() => {
            const branding = this.branding();
            if (branding && !filled) {
                filled = true;
                this.form.setValue({name: branding.name ?? "", primaryColor: branding.primaryColor ?? ""});
            }
        });

        this.actions$
            .pipe(ofType(BrandingActions.uploadAdminLogoSuccess), takeUntilDestroyed())
            .subscribe(() => this.notify("branding.snack.logoSaved"));
        this.actions$
            .pipe(ofType(BrandingActions.deleteAdminLogoSuccess), takeUntilDestroyed())
            .subscribe(() => this.notify("branding.snack.logoRemoved"));
        this.actions$
            .pipe(
                ofType(BrandingActions.uploadAdminLogoFailure, BrandingActions.deleteAdminLogoFailure),
                takeUntilDestroyed(),
            )
            .subscribe(({error}) => this.notifyError("branding.snack.failed", {error}));
    }

    /** The colour the picker shows: the entered one, else the default. */
    shownColor(): string {
        const entered = this.form.controls.primaryColor.value.trim();
        return isHexColor(entered) ? entered : (this.branding()?.defaultPrimaryColor ?? "#000000");
    }

    onColorPicked(event: Event): void {
        this.form.controls.primaryColor.setValue((event.target as HTMLInputElement).value);
        this.form.controls.primaryColor.markAsDirty();
    }

    onLogoChosen(input: HTMLInputElement): void {
        const file = input.files?.[0];
        // Cleared so choosing the same file again fires (change) again.
        input.value = "";
        if (!file) {
            return;
        }
        if (file.size > MAX_LOGO_BYTES) {
            this.notifyError("branding.snack.logoTooLarge", {max: this.maxLogoSize});
            return;
        }
        this.store.dispatch(BrandingActions.uploadAdminLogo({tenantId: this.tenant.id, file}));
    }

    onLogoRemoved(): void {
        this.store.dispatch(BrandingActions.deleteAdminLogo({tenantId: this.tenant.id}));
    }

    private notify(key: string): void {
        this.snackBar.open(this.transloco.translate(key), this.transloco.translate("common.ok"), {duration: 3000});
    }

    private notifyError(key: string, params: Record<string, unknown>): void {
        this.snackBar.open(this.transloco.translate(key, params), this.transloco.translate("common.ok"), {
            duration: 10000,
            panelClass: "error-snackbar",
        });
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildTenantBrandingRequest(this.form.getRawValue()));
    }
}
