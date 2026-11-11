import {Component, effect, inject, input, output} from "@angular/core";
import {Store} from "@ngrx/store";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {Tenant} from "../../models/tenant.model";
import * as BrandingActions from "../../store/branding/branding.actions";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {Actions, ofType} from "@ngrx/effects";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {MatSnackBar} from "@angular/material/snack-bar";
import {LoginLinkComponent} from "../shared/login-link/login-link.component";

/**
 * How the tenant's users see the interface: name, colour and logo - changed in the branding dialog - and the address of
 * the tenant's own login page, which shows that look before the login.
 */
@Component({
    selector: "app-tenant-branding-summary",
    imports: [MatButtonModule, MatIconModule, MatProgressBarModule, TranslocoPipe, LoginLinkComponent],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "branding.summary.title" | transloco }}</span>
                <span class="muted">{{ "branding.summary.intro" | transloco }}</span>
                <span class="spacer"></span>
                <button mat-stroked-button (click)="edit.emit()">
                    <mat-icon>palette</mat-icon>
                    {{ "branding.summary.edit" | transloco }}
                </button>
            </div>
            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }
            @if (branding(); as branding) {
                <dl>
                    <dt>{{ "common.name" | transloco }}</dt>
                    <dd>
                        {{ branding.name ?? branding.defaultName }}
                        @if (branding.name === null) {
                            <span class="app-tag">{{ "branding.summary.defaultTag" | transloco }}</span>
                        }
                    </dd>
                    <dt>{{ "branding.dialog.primaryColor" | transloco }}</dt>
                    <dd>
                        <span
                            class="swatch"
                            [style.background]="branding.primaryColor ?? branding.defaultPrimaryColor"
                        ></span>
                        <span class="mono">{{ branding.primaryColor ?? branding.defaultPrimaryColor }}</span>
                        @if (branding.primaryColor === null) {
                            <span class="app-tag">{{ "branding.summary.defaultTag" | transloco }}</span>
                        }
                    </dd>
                    <dt>{{ "branding.dialog.logo" | transloco }}</dt>
                    <dd>
                        @if (logoUrl(); as logo) {
                            <img class="logo" [src]="logo" [alt]="'branding.dialog.logo' | transloco" />
                        } @else {
                            <span class="muted">{{ "branding.dialog.noLogo" | transloco }}</span>
                        }
                    </dd>
                    <dt>{{ "branding.loginLink.title" | transloco }}</dt>
                    <dd class="login-page">
                        <app-login-link [loginKey]="branding.loginKey" [renewable]="true" (renew)="renewLoginKey()" />
                        <span class="muted">{{ "branding.loginLink.adminHint" | transloco }}</span>
                    </dd>
                </dl>
            }
        </div>
    `,
    styles: [
        `
            dl {
                display: grid;
                grid-template-columns: max-content minmax(0, 1fr);
                gap: 12px 24px;
                margin: 0;
                padding: 16px;
            }

            dt {
                color: var(--app-text-2);
            }

            dd {
                margin: 0;
                display: flex;
                align-items: center;
                gap: 8px;
                flex-wrap: wrap;
            }

            .swatch {
                width: 20px;
                height: 20px;
                border-radius: 6px;
                border: 1px solid var(--app-border);
            }

            .login-page {
                flex-direction: column;
                align-items: flex-start;
            }

            .logo {
                max-height: 48px;
                max-width: 160px;
                object-fit: contain;
            }
        `,
    ],
})
export class TenantBrandingSummaryComponent {
    private store = inject(Store);

    readonly tenant = input.required<Tenant>();
    readonly edit = output<void>();

    readonly branding = this.store.selectSignal(BrandingSelectors.selectAdminBranding);
    readonly logoUrl = this.store.selectSignal(BrandingSelectors.selectAdminLogoUrl);
    readonly loading = this.store.selectSignal(BrandingSelectors.selectAdminBrandingLoading);

    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);

    constructor() {
        effect(() => this.store.dispatch(BrandingActions.loadAdminBranding({tenantId: this.tenant().id})));
        inject(Actions)
            .pipe(
                ofType(BrandingActions.renewAdminLoginKeySuccess, BrandingActions.renewAdminLoginKeyFailure),
                takeUntilDestroyed(),
            )
            .subscribe(action => {
                const message =
                    action.type === BrandingActions.renewAdminLoginKeySuccess.type
                        ? this.transloco.translate("branding.snack.linkRenewed")
                        : this.transloco.translate("branding.snack.failed", {error: action.error});
                this.snackBar.open(message, this.transloco.translate("common.ok"), {duration: 3000});
            });
    }

    renewLoginKey(): void {
        this.store.dispatch(BrandingActions.renewAdminLoginKey({tenantId: this.tenant().id}));
    }
}
