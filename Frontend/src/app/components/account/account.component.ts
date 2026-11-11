import * as AuthSelectors from "../../store/auth/auth.selectors";
import {Component, inject, viewChild} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {FormBuilder, FormGroupDirective, ReactiveFormsModule, Validators} from "@angular/forms";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatButtonModule} from "@angular/material/button";
import {MatProgressSpinnerModule} from "@angular/material/progress-spinner";
import {MatSnackBar} from "@angular/material/snack-bar";
import * as AccountActions from "../../store/account/account.actions";
import * as AccountSelectors from "../../store/account/account.selectors";
import {initials} from "../../utils/shell.util";
import {PASSWORD_MIN_LENGTH, passwordsMatch} from "../../utils/validators.util";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {McpConnectComponent} from "./mcp-connect.component";

/**
 * Tenant area page "Konto": who is logged in, changing the own password, and - when the tenant has an MCP key - how to
 * connect Claude Code or Copilot.
 */
@Component({
    selector: "app-account",
    imports: [
        TranslocoPipe,
        ReactiveFormsModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatProgressSpinnerModule,
        PageHeaderComponent,
        McpConnectComponent,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'account.title' | transloco"
                [subtitle]="(wamEnabled() ? 'account.subtitleWam' : 'account.subtitle') | transloco"
            />

            <div class="app-panel narrow">
                <div class="app-panel-head">
                    <span class="app-panel-title">{{ "account.signedInAs" | transloco }}</span>
                </div>
                <div class="app-panel-body">
                    @if (account(); as account) {
                        <div class="person">
                            <span class="app-avatar large">{{ initialsOf(account.displayName) }}</span>
                            <dl class="account-info">
                                <dt>{{ "account.username" | transloco }}</dt>
                                <dd class="mono">{{ account.username }}</dd>
                                <dt>{{ "common.name" | transloco }}</dt>
                                <dd>{{ account.displayName }}</dd>
                                <dt>{{ "account.tenant" | transloco }}</dt>
                                <dd>{{ account.tenantName }}</dd>
                            </dl>
                        </div>
                    } @else if (loading()) {
                        <mat-spinner diameter="30"></mat-spinner>
                    }
                </div>
            </div>

            @if (!wamEnabled()) {
                <div class="app-panel narrow">
                    <div class="app-panel-head">
                        <span class="app-panel-title">{{ "account.changePassword" | transloco }}</span>
                    </div>
                    <div class="app-panel-body">
                        <form [formGroup]="form" class="password-form" (ngSubmit)="changePassword()">
                            <mat-form-field appearance="outline">
                                <mat-label>{{ "account.currentPassword" | transloco }}</mat-label>
                                <input
                                    matInput
                                    type="password"
                                    formControlName="currentPassword"
                                    required
                                    autocomplete="current-password"
                                />
                                @if (
                                    form.controls.currentPassword.hasError("required") &&
                                    form.controls.currentPassword.touched
                                ) {
                                    <mat-error>{{ "account.currentPasswordRequired" | transloco }}</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field appearance="outline">
                                <mat-label>{{ "account.newPassword" | transloco }}</mat-label>
                                <input
                                    matInput
                                    type="password"
                                    formControlName="newPassword"
                                    required
                                    autocomplete="new-password"
                                />
                                <mat-hint>{{ "account.minLength" | transloco: {min: passwordMinLength} }}</mat-hint>
                                @if (
                                    form.controls.newPassword.hasError("required") && form.controls.newPassword.touched
                                ) {
                                    <mat-error>{{ "account.newPasswordRequired" | transloco }}</mat-error>
                                }
                                @if (form.controls.newPassword.hasError("minlength")) {
                                    <mat-error>{{
                                        "account.minLength" | transloco: {min: passwordMinLength}
                                    }}</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field appearance="outline">
                                <mat-label>{{ "account.repeatPassword" | transloco }}</mat-label>
                                <input
                                    matInput
                                    type="password"
                                    formControlName="repeatPassword"
                                    required
                                    autocomplete="new-password"
                                />
                                @if (
                                    form.controls.repeatPassword.hasError("required") &&
                                    form.controls.repeatPassword.touched
                                ) {
                                    <mat-error>{{ "account.repeatPasswordRequired" | transloco }}</mat-error>
                                }
                            </mat-form-field>
                            <!-- A group error: mat-error only shows a control's own errors. -->
                            @if (form.hasError("passwordMismatch")) {
                                <div class="form-error">{{ "account.passwordMismatch" | transloco }}</div>
                            }
                            <div>
                                <button mat-flat-button type="submit" [disabled]="form.invalid || saving()">
                                    {{ "account.changePassword" | transloco }}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            }

            @if (account()?.mcpServer) {
                <app-mcp-connect class="narrow" [admin]="account()?.admin ?? false" />
            }
        </div>
    `,
    styles: [
        `
            .narrow {
                max-width: 720px;
            }

            .person {
                display: flex;
                align-items: flex-start;
                gap: 16px;
            }

            .app-avatar.large {
                width: 44px;
                height: 44px;
                font-size: 15px;
            }

            .account-info {
                display: grid;
                grid-template-columns: max-content 1fr;
                gap: 8px 24px;
                margin: 0;
            }

            .account-info dt {
                color: var(--app-text-2);
            }

            .account-info dd {
                margin: 0;
            }

            .account-info dd.mono {
                font-size: 13px;
            }

            .password-form {
                display: flex;
                flex-direction: column;
            }

            .form-error {
                color: var(--app-err);
                font-size: 13px;
                margin-bottom: 12px;
            }
        `,
    ],
})
export class AccountComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private snackBar = inject(MatSnackBar);
    private fb = inject(FormBuilder);
    private transloco = inject(TranslocoService);

    readonly account = this.store.selectSignal(AccountSelectors.selectAccount);
    readonly loading = this.store.selectSignal(AccountSelectors.selectAccountLoading);
    readonly saving = this.store.selectSignal(AccountSelectors.selectPasswordSaving);
    readonly passwordMinLength = PASSWORD_MIN_LENGTH;
    /** With WAM there is no password to change. */
    readonly wamEnabled = this.store.selectSignal(AuthSelectors.selectWamEnabled);

    form = this.fb.nonNullable.group(
        {
            currentPassword: ["", Validators.required],
            newPassword: ["", [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH)]],
            repeatPassword: ["", Validators.required],
        },
        {validators: passwordsMatch("newPassword", "repeatPassword")},
    );
    // resetForm() on the directive (not form.reset()) also clears the "submitted" state - otherwise
    // the emptied, required fields show as invalid right after a successful change.
    private formDirective = viewChild.required(FormGroupDirective);

    constructor() {
        this.actions$.pipe(ofType(AccountActions.changePasswordSuccess), takeUntilDestroyed()).subscribe(() => {
            this.formDirective().resetForm();
            this.snackBar.open(
                this.transloco.translate("account.snack.passwordChanged"),
                this.transloco.translate("common.ok"),
                {duration: 3000},
            );
        });
        // The form keeps its input on a failure (e.g. a wrong current password).
        this.actions$
            .pipe(ofType(AccountActions.changePasswordFailure), takeUntilDestroyed())
            .subscribe(({error}) =>
                this.snackBar.open(
                    this.transloco.translate("account.snack.error", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                ),
            );
    }

    initialsOf(name: string): string {
        return initials(name);
    }

    changePassword(): void {
        if (this.form.invalid) {
            return;
        }
        const {currentPassword, newPassword} = this.form.getRawValue();
        this.store.dispatch(AccountActions.changePassword({request: {currentPassword, newPassword}}));
    }
}
