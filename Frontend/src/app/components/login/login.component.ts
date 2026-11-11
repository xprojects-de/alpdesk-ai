import {Component, effect, inject, input, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {Router} from "@angular/router";
import {Store} from "@ngrx/store";
import {filter, take} from "rxjs/operators";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatButtonModule} from "@angular/material/button";
import {MatProgressSpinnerModule} from "@angular/material/progress-spinner";
import {MatIconModule} from "@angular/material/icon";
import {AuthService} from "../../services/auth.service";
import {BrandingService} from "../../services/branding.service";
import * as AuthActions from "../../store/auth/auth.actions";
import * as AuthSelectors from "../../store/auth/auth.selectors";
import * as BrandingActions from "../../store/branding/branding.actions";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import {BACKDROP_SPARKS} from "./login-backdrop";
import {TranslocoPipe} from "@jsverse/transloco";

@Component({
    selector: "app-login",
    imports: [
        ReactiveFormsModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatProgressSpinnerModule,
        MatIconModule,
        TranslocoPipe,
    ],
    template: `
        <!--
            /login is neutral on purpose: one login page for every tenant (docs/architecture/branding.md) - no name, no
            logo. A tenant's own login page /login/<key> shows its logo, name and colour.
        -->
        <div class="login" [class.branded]="brand()">
            <aside class="side">
                <svg class="backdrop" viewBox="0 0 900 640" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
                    @for (spark of sparks; track $index) {
                        <path class="spark" [attr.d]="spark" />
                    }
                </svg>
                @if (brand(); as brand) {
                    <div class="brand">
                        @if (logoUrl(); as logo) {
                            <div class="logo-tile"><img [src]="logo" alt="" /></div>
                        }
                        <span class="brand-name">{{ brand.name }}</span>
                    </div>
                } @else {
                    <div class="mark"><mat-icon>auto_awesome</mat-icon></div>
                }
            </aside>

            <main class="form-side">
                <div class="form-box">
                    @if (brand(); as brand) {
                        <div class="brand-inline">
                            @if (logoUrl(); as logo) {
                                <img [src]="logo" alt="" />
                            }
                            <span>{{ brand.name }}</span>
                        </div>
                    }
                    <h1>{{ "login.title" | transloco }}</h1>
                    @if (wamEnabled()) {
                        <div class="wam">
                            @if (loading()) {
                                <mat-spinner diameter="32" />
                                <p>{{ "login.wamSigningIn" | transloco }}</p>
                            } @else if (wamChoice(); as choice) {
                                <p class="wam-title">{{ "login.chooseTenant" | transloco }}</p>
                                <div class="tenant-choice">
                                    @for (tenant of choice; track tenant.id) {
                                        <button mat-stroked-button type="button" (click)="wamSignIn(tenant.id)">
                                            @if (tenant.loginKey; as key) {
                                                @if (!missingLogos().has(key)) {
                                                    <img
                                                        class="tenant-logo"
                                                        [src]="tenantLogo(key)"
                                                        alt=""
                                                        (error)="logoMissing(key)"
                                                    />
                                                }
                                            }
                                            <span>{{ tenant.name }}</span>
                                        </button>
                                    }
                                </div>
                            } @else if (wamWaiting()) {
                                <mat-icon class="wam-icon">hourglass_top</mat-icon>
                                <p class="wam-title">{{ "login.wamRequested" | transloco }}</p>
                                <p>{{ "login.wamWaiting" | transloco }}</p>
                                <button mat-stroked-button type="button" (click)="wamSignIn()">
                                    <mat-icon>refresh</mat-icon>
                                    {{ "login.checkAgain" | transloco }}
                                </button>
                            } @else if (signedOut()) {
                                <mat-icon class="wam-icon">logout</mat-icon>
                                <p class="wam-title">{{ "login.signedOut" | transloco }}</p>
                                <button mat-flat-button type="button" (click)="wamSignIn()">
                                    {{ "login.signInAgain" | transloco }}
                                </button>
                            } @else if (wamError(); as wamError) {
                                <div class="error-message">
                                    <mat-icon>error</mat-icon>
                                    <span>{{ wamError }}</span>
                                </div>
                                <button mat-stroked-button type="button" (click)="wamSignIn()">
                                    <mat-icon>refresh</mat-icon>
                                    {{ "login.tryAgain" | transloco }}
                                </button>
                            }
                        </div>
                        @if (!showPasswordLogin()) {
                            <button mat-button type="button" class="admin-link" (click)="showPasswordLogin.set(true)">
                                {{ "login.asAdmin" | transloco }}
                            </button>
                        }
                    } @else {
                        <p class="sub">{{ "login.withPassword" | transloco }}</p>
                    }
                    @if (!wamEnabled() || showPasswordLogin()) {
                        <form [formGroup]="loginForm" (ngSubmit)="onSubmit()" class="login-form">
                            <mat-form-field appearance="outline">
                                <mat-label>{{ "login.username" | transloco }}</mat-label>
                                <input matInput formControlName="username" required autocomplete="username" />
                                @if (
                                    loginForm.get("username")?.hasError("required") &&
                                    loginForm.get("username")?.touched
                                ) {
                                    <mat-error>{{ "login.usernameRequired" | transloco }}</mat-error>
                                }
                            </mat-form-field>

                            <mat-form-field appearance="outline">
                                <mat-label>{{ "login.password" | transloco }}</mat-label>
                                <input
                                    matInput
                                    type="password"
                                    formControlName="password"
                                    required
                                    autocomplete="current-password"
                                />
                                @if (
                                    loginForm.get("password")?.hasError("required") &&
                                    loginForm.get("password")?.touched
                                ) {
                                    <mat-error>{{ "login.passwordRequired" | transloco }}</mat-error>
                                }
                            </mat-form-field>

                            @if (error(); as error) {
                                <div class="error-message">
                                    <mat-icon>error</mat-icon>
                                    <span>{{ error }}</span>
                                </div>
                            }

                            <button
                                mat-flat-button
                                type="submit"
                                [disabled]="!loginForm.valid || loading()"
                                class="login-button"
                            >
                                @if (loading()) {
                                    <mat-spinner diameter="18"></mat-spinner>
                                    <span>{{ "login.signingIn" | transloco }}</span>
                                } @else {
                                    <span>{{ "login.signIn" | transloco }}</span>
                                }
                            </button>
                        </form>
                    }
                </div>
            </main>
        </div>
    `,
    styles: [
        `
            .login {
                display: grid;
                grid-template-columns: 320px minmax(0, 1fr);
                min-height: 100vh;
            }

            /*
             * Neutral greys instead of a brand colour - /login carries no brand (NEUTRAL_BRANDING). Set here, they
             * reach the fields and buttons too. A tenant's login page keeps the colours BrandingEffects set.
             */
            .login:not(.branded) {
                --app-brand: light-dark(#3a3d38, #e5e7e2);
                --app-on-brand: light-dark(#ffffff, #1b1d1a);
                --mat-sys-primary: light-dark(#424242, #d4d4d4);
                --mat-sys-on-primary: light-dark(#ffffff, #1f1f1f);
                /* Taken from --app-brand where it is declared (html) - declared again for these greys. */
                --mat-button-filled-container-color: var(--app-brand);
                --mat-button-filled-label-text-color: var(--app-on-brand);
            }

            /* One look in both schemes: a dark panel with light sparks. */
            .side {
                position: relative;
                overflow: hidden;
                background: #23271f;
                color: #e9ece4;
                padding: 32px;
            }

            .backdrop {
                position: absolute;
                inset: 0;
                width: 100%;
                height: 100%;
                pointer-events: none;
            }

            .backdrop .spark {
                fill: #e9ece4;
                opacity: 0.22;
            }

            .mark,
            .brand {
                position: relative;
            }

            /* The tenant's colour as the panel, its logo on a light tile - a logo is made for a light ground. */
            .branded .side {
                background: var(--app-brand);
                color: var(--app-on-brand);
            }

            .branded .backdrop .spark {
                fill: var(--app-on-brand);
                opacity: 0.16;
            }

            .brand {
                display: flex;
                flex-direction: column;
                align-items: flex-start;
                gap: 16px;
            }

            .logo-tile {
                background: #ffffff;
                border-radius: 14px;
                padding: 12px;
                display: grid;
                place-items: center;
            }

            .logo-tile img {
                display: block;
                max-width: 160px;
                max-height: 72px;
                object-fit: contain;
            }

            .brand-name {
                font-size: 20px;
                font-weight: 650;
                letter-spacing: -0.01em;
                overflow-wrap: anywhere;
            }

            /* Below 860px the panel is gone: the brand sits above the form. */
            .brand-inline {
                display: none;
                align-items: center;
                gap: 12px;
                margin-bottom: 24px;
                font-size: 17px;
                font-weight: 650;
            }

            .brand-inline img {
                max-width: 120px;
                max-height: 40px;
                object-fit: contain;
            }

            .mark {
                width: 40px;
                height: 40px;
                border-radius: 11px;
                background: #e9ece4;
                color: #23271f;
                display: grid;
                place-items: center;
            }

            .form-side {
                display: grid;
                place-items: center;
                padding: 32px 16px;
                background: var(--app-surface);
            }

            .form-box {
                width: 100%;
                max-width: 360px;
            }

            h1 {
                font-size: 22px;
                font-weight: 650;
                letter-spacing: -0.015em;
                margin: 0 0 4px;
            }

            .sub {
                color: var(--app-text-2);
                margin: 0 0 24px;
            }

            .login-form {
                display: flex;
                flex-direction: column;
                gap: 4px;
            }

            mat-form-field {
                width: 100%;
            }

            .login-button {
                width: 100%;
                height: 44px;
                font-size: 14px;
                margin-top: 4px;
            }

            .login-button mat-spinner {
                display: inline-block;
                margin-right: 8px;
                vertical-align: middle;
            }

            .error-message {
                display: flex;
                align-items: center;
                gap: 8px;
                color: var(--app-err);
                background: color-mix(in oklab, var(--app-err) 10%, var(--app-surface));
                padding: 10px 12px;
                border-radius: 8px;
                font-size: 13.5px;
                margin-bottom: 12px;
            }

            .error-message mat-icon {
                font-size: 20px;
                width: 20px;
                height: 20px;
            }

            .wam {
                display: flex;
                flex-direction: column;
                align-items: center;
                gap: 8px;
                text-align: center;
                margin: 24px 0 16px;
            }

            .wam-icon {
                font-size: 40px;
                width: 40px;
                height: 40px;
                color: var(--app-text-2);
            }

            .wam-title {
                font-weight: 600;
                font-size: 16px;
                margin: 0;
            }

            .tenant-choice {
                display: flex;
                flex-direction: column;
                gap: 8px;
                width: 100%;
            }

            .tenant-choice button {
                width: 100%;
                height: auto;
                min-height: 48px;
                justify-content: flex-start;
            }

            .tenant-logo {
                width: 28px;
                height: 28px;
                object-fit: contain;
                margin-right: 12px;
                vertical-align: middle;
            }

            .admin-link {
                width: 100%;
                margin-bottom: 8px;
            }

            @media (max-width: 860px) {
                .login {
                    grid-template-columns: minmax(0, 1fr);
                }

                .side {
                    display: none;
                }

                .brand-inline {
                    display: flex;
                }
            }
        `,
    ],
})
export class LoginComponent {
    readonly sparks = BACKDROP_SPARKS;

    /** The key of a tenant's own login page (route /login/:loginKey); absent on /login. */
    readonly loginKey = input<string>();

    private fb = inject(FormBuilder);
    private store = inject(Store);
    private router = inject(Router);
    private authService = inject(AuthService);
    private brandingService = inject(BrandingService);

    readonly loginForm = this.fb.nonNullable.group({
        username: ["", Validators.required],
        password: ["", Validators.required],
    });

    readonly loading = this.store.selectSignal(AuthSelectors.selectAuthLoading);
    readonly error = this.store.selectSignal(AuthSelectors.selectAuthError);
    readonly wamEnabled = this.store.selectSignal(AuthSelectors.selectWamEnabled);
    readonly wamWaiting = this.store.selectSignal(AuthSelectors.selectWamWaiting);
    readonly wamError = this.store.selectSignal(AuthSelectors.selectWamError);
    /** A person with a user in several tenants chooses one - each shown with its logo. */
    readonly wamChoice = this.store.selectSignal(AuthSelectors.selectWamChoice);
    /** The admin keeps its password login - hidden behind a link while tenant users sign in through WAM. */
    readonly showPasswordLogin = signal(false);
    /** Logged out on purpose: no sign-in at once, the page offers it. */
    readonly signedOut = signal(this.authService.isSignedOut());
    /** The tenant's look on its own login page; null on /login and for an unknown key. */
    readonly brand = this.store.selectSignal(BrandingSelectors.selectLoginBranding);
    readonly logoUrl = this.store.selectSignal(BrandingSelectors.selectLoginLogoUrl);

    constructor() {
        effect(() => {
            const loginKey = this.loginKey();
            this.store.dispatch(
                loginKey ? BrandingActions.loadLoginBranding({loginKey}) : BrandingActions.clearLoginBranding(),
            );
        });

        // Loaded again here: a logout resets the store, and the login page decides by it.
        this.store.dispatch(AuthActions.loadWamStatus());
        this.store
            .select(AuthSelectors.selectWamEnabled)
            .pipe(
                filter(enabled => enabled && !this.signedOut()),
                take(1),
                takeUntilDestroyed(),
            )
            .subscribe(() => this.store.dispatch(AuthActions.wamSignIn()));

        // Already logged in (or just logged in): leave the login page for the user's area.
        this.store
            .select(AuthSelectors.selectHomePath)
            .pipe(
                filter(path => path !== null),
                takeUntilDestroyed(),
            )
            .subscribe(path => this.router.navigate([path]).then());
    }

    /** tenantId: the tenant the person chose among its tenants. */
    wamSignIn(tenantId?: number): void {
        this.signedOut.set(false);
        this.store.dispatch(AuthActions.wamSignIn(tenantId === undefined ? {} : {tenantId}));
    }

    /** Tenants of the choice without a logo (its address answered 404). */
    readonly missingLogos = signal<ReadonlySet<string>>(new Set());

    logoMissing(loginKey: string): void {
        this.missingLogos.update(keys => new Set([...keys, loginKey]));
    }

    /** The tenant's logo through its login page's anonymous address - hidden when it has none. */
    tenantLogo(loginKey: string): string {
        return this.brandingService.loginLogoUrl(loginKey);
    }

    /** On a tenant's own login page its key goes along: it picks the tenant for a name that is in several. */
    onSubmit(): void {
        if (this.loginForm.valid) {
            const loginKey = this.loginKey();
            this.store.dispatch(
                AuthActions.login({credentials: {...this.loginForm.getRawValue(), ...(loginKey ? {loginKey} : {})}}),
            );
        }
    }
}
