import {Component, computed, DOCUMENT, inject, input, output, signal} from "@angular/core";
import {takeUntilDestroyed, toSignal} from "@angular/core/rxjs-interop";
import {NavigationEnd, Router, RouterLink, RouterLinkActive} from "@angular/router";
import {filter, map} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatMenuModule} from "@angular/material/menu";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatDividerModule} from "@angular/material/divider";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {
    applyColorScheme,
    ColorScheme,
    localStorageOf,
    readColorScheme,
    storeColorScheme,
} from "../../../utils/color-scheme.util";
import {initials, shellNavItem, ShellNavGroup} from "../../../utils/shell.util";
import {LANGUAGES} from "../../../utils/language.util";
import * as LanguageActions from "../../../store/language/language.actions";
import * as LanguageSelectors from "../../../store/language/language.selectors";

const SCHEMES: {value: ColorScheme; label: string; icon: string}[] = [
    {value: "system", label: "shell.scheme.system", icon: "contrast"},
    {value: "light", label: "shell.scheme.light", icon: "light_mode"},
    {value: "dark", label: "shell.scheme.dark", icon: "dark_mode"},
];

/**
 * The frame of both areas - the admin's dashboard and the tenant area: a sidebar with the brand, the menu and the
 * signed-in person (with the colour scheme and the language in its menu), a top bar with where one is, and the page.
 * The menu's labels are translation keys. The brand colour is only an accent (the active entry,
 * buttons): it must look right with any tenant's colour. Below 960px the sidebar slides in over the page.
 */
import {TenantChoice} from "../../../models/auth.model";
@Component({
    selector: "app-shell",
    imports: [
        RouterLink,
        RouterLinkActive,
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatTooltipModule,
        MatDividerModule,
        TranslocoPipe,
    ],
    template: `
        <aside class="sidebar" [class.open]="navOpen()">
            <div class="brand">
                @if (logoUrl(); as logo) {
                    <img class="brand-logo" [src]="logo" alt="" />
                } @else {
                    <div class="brand-mark">{{ brandInitial() }}</div>
                }
                <div class="brand-text">
                    <div class="brand-name">{{ name() }}</div>
                    @if (subtitle()) {
                        <div class="brand-sub">{{ subtitle() }}</div>
                    }
                </div>
            </div>

            <nav class="nav" [attr.aria-label]="'shell.mainMenu' | transloco">
                @for (group of navGroups(); track $index) {
                    @if (group.label) {
                        <div class="nav-label">{{ group.label | transloco }}</div>
                    }
                    @for (item of group.items; track item.path) {
                        <a
                            [routerLink]="item.path"
                            routerLinkActive="active"
                            #link="routerLinkActive"
                            (click)="closeNav()"
                        >
                            <mat-icon [fontSet]="link.isActive ? 'material-icons' : ''">{{ item.icon }}</mat-icon>
                            <span>{{ item.label | transloco }}</span>
                        </a>
                    }
                }
            </nav>

            <div class="user">
                <span class="app-avatar">{{ userInitials() }}</span>
                <div class="user-meta">
                    <div class="user-name">{{ userName() }}</div>
                    <div class="user-role">{{ userRole() }}</div>
                </div>
                <button mat-icon-button [matMenuTriggerFor]="userMenu" [attr.aria-label]="'shell.userMenu' | transloco">
                    <mat-icon>more_vert</mat-icon>
                </button>
            </div>
            @if (version()) {
                <div class="version" [matTooltip]="versionTooltip()" matTooltipPosition="right">
                    {{ "shell.version" | transloco: {version: version()} }}
                </div>
            }
        </aside>

        <mat-menu #userMenu="matMenu" xPosition="before" yPosition="above">
            @if (tenants().length > 1) {
                <!-- The person's users in other tenants (docs/architecture/tenancy.md "Who does what"). -->
                <div class="menu-label">{{ "shell.tenant" | transloco }}</div>
                @for (tenant of tenants(); track tenant.id) {
                    <button
                        mat-menu-item
                        [disabled]="tenant.id === currentTenantId()"
                        (click)="switchTenant.emit(tenant)"
                    >
                        <mat-icon>domain</mat-icon>
                        <span>{{ tenant.name }}</span>
                        @if (tenant.id === currentTenantId()) {
                            <mat-icon class="checked">check</mat-icon>
                        }
                    </button>
                }
                <mat-divider />
            }
            <div class="menu-label">{{ "shell.appearance" | transloco }}</div>
            @for (scheme of schemes; track scheme.value) {
                <button mat-menu-item (click)="chooseScheme(scheme.value)">
                    <mat-icon>{{ scheme.icon }}</mat-icon>
                    <span>{{ scheme.label | transloco }}</span>
                    @if (colorScheme() === scheme.value) {
                        <mat-icon class="checked">check</mat-icon>
                    }
                </button>
            }
            <mat-divider />
            <div class="menu-label">{{ "language.label" | transloco }}</div>
            <!-- Each language by its own name, so it is found whatever language the page speaks. -->
            @for (entry of languages; track entry.value) {
                <button mat-menu-item [attr.lang]="entry.value" (click)="chooseLanguage(entry.value)">
                    <mat-icon>translate</mat-icon>
                    <span>{{ entry.label }}</span>
                    @if (language() === entry.value) {
                        <mat-icon class="checked">check</mat-icon>
                    }
                </button>
            }
            <mat-divider />
            <button mat-menu-item (click)="logout.emit()">
                <mat-icon>logout</mat-icon>
                <span>{{ "shell.logout" | transloco }}</span>
            </button>
        </mat-menu>

        @if (navOpen()) {
            <button
                type="button"
                class="scrim"
                [attr.aria-label]="'shell.closeMenu' | transloco"
                (click)="closeNav()"
            ></button>
        }

        <div class="main">
            <header class="topbar">
                <button
                    mat-icon-button
                    class="menu-button"
                    (click)="navOpen.set(true)"
                    [attr.aria-label]="'shell.openMenu' | transloco"
                >
                    <mat-icon>menu</mat-icon>
                </button>
                <div class="crumbs">
                    @if (current(); as current) {
                        @if (current.group) {
                            <span>{{ current.group | transloco }}</span>
                            <mat-icon>chevron_right</mat-icon>
                        }
                        <b>{{ current.label | transloco }}</b>
                    }
                </div>
                <span class="spacer"></span>
                @if (backendReachable() === true) {
                    <span class="health">{{ "shell.connected" | transloco }}</span>
                }
            </header>

            <!-- Only once a check has actually failed (false, not the unknown null): nothing is saved while the
                 server is away, so this is a banner, not a small icon. -->
            @if (backendReachable() === false) {
                <div class="offline">
                    <mat-icon>cloud_off</mat-icon>
                    <span>{{ "shell.offline" | transloco }}</span>
                    <button
                        mat-icon-button
                        (click)="retryHealth.emit()"
                        [matTooltip]="'shell.checkAgain' | transloco"
                        [attr.aria-label]="'shell.checkAgain' | transloco"
                    >
                        <mat-icon>refresh</mat-icon>
                    </button>
                </div>
            }

            <main class="content">
                <ng-content />
            </main>
        </div>
    `,
    styles: [
        `
            :host {
                display: grid;
                grid-template-columns: 252px minmax(0, 1fr);
                height: 100vh;
                /* A phone's browser counts its hidden toolbars into 100vh: the page would scroll and push the top
                   bar under the address bar. dvh is the area really visible. */
                height: 100dvh;
                /* Opened from a phone's home screen the page reaches the screen's edges (index.html, viewport-fit):
                   the notch and the home indicator stay free. In a browser tab every inset is 0. */
                box-sizing: border-box;
                padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom)
                    env(safe-area-inset-left);
            }

            .sidebar {
                background: var(--app-surface-2);
                border-right: 1px solid var(--app-border);
                display: flex;
                flex-direction: column;
                min-height: 0;
            }

            .brand {
                display: flex;
                align-items: center;
                gap: 10px;
                padding: 16px 16px 12px;
            }

            .brand-mark {
                width: 34px;
                height: 34px;
                border-radius: 9px;
                background: var(--app-brand);
                color: var(--app-on-brand);
                display: grid;
                place-items: center;
                font-weight: 700;
                font-size: 15px;
                flex: 0 0 auto;
            }

            .brand-logo {
                max-width: 64px;
                max-height: 34px;
                object-fit: contain;
                flex: 0 0 auto;
            }

            .brand-text {
                min-width: 0;
            }

            .brand-name {
                font-weight: 600;
                font-size: 14px;
                line-height: 1.25;
                overflow-wrap: anywhere;
            }

            .brand-sub {
                font-size: 11.5px;
                color: var(--app-text-3);
            }

            .nav {
                flex: 1 1 auto;
                overflow-y: auto;
                padding: 4px 10px 12px;
                display: flex;
                flex-direction: column;
                gap: 2px;
            }

            .nav-label {
                font-size: 11px;
                font-weight: 600;
                letter-spacing: 0.06em;
                text-transform: uppercase;
                color: var(--app-text-3);
                padding: 14px 10px 6px;
            }

            .nav a {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 8px 10px;
                border-radius: 8px;
                color: var(--app-text-2);
                text-decoration: none;
                font-weight: 500;
                font-size: 13.5px;
            }

            .nav a mat-icon {
                font-size: 20px;
                width: 20px;
                height: 20px;
            }

            .nav a:hover {
                background: var(--app-surface-3);
                color: var(--app-text);
            }

            .nav a.active {
                background: var(--app-brand-soft);
                color: var(--app-brand-text);
                font-weight: 600;
            }

            .user {
                border-top: 1px solid var(--app-border);
                padding: 10px 6px 10px 14px;
                display: flex;
                align-items: center;
                gap: 10px;
            }

            .user .app-avatar {
                width: 32px;
                height: 32px;
                font-size: 12.5px;
            }

            .user-meta {
                min-width: 0;
                flex: 1 1 auto;
            }

            .user-name {
                font-weight: 600;
                font-size: 13px;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }

            .user-role {
                font-size: 11.5px;
                color: var(--app-text-3);
            }

            .version {
                font-size: 11px;
                color: var(--app-text-3);
                padding: 0 16px 12px;
                align-self: flex-start;
            }

            .menu-label {
                font-size: 11px;
                font-weight: 600;
                letter-spacing: 0.06em;
                text-transform: uppercase;
                color: var(--app-text-3);
                padding: 8px 16px 4px;
            }

            .checked {
                margin: 0 0 0 auto !important;
                color: var(--app-brand-text) !important;
            }

            .main {
                display: flex;
                flex-direction: column;
                min-width: 0;
                min-height: 0;
            }

            .topbar {
                height: 56px;
                flex: 0 0 auto;
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 0 24px;
                background: var(--app-surface);
                border-bottom: 1px solid var(--app-border);
            }

            .menu-button {
                display: none;
            }

            .crumbs {
                display: flex;
                align-items: center;
                gap: 6px;
                color: var(--app-text-3);
                font-size: 13px;
                min-width: 0;
            }

            .crumbs b {
                color: var(--app-text);
                font-weight: 600;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }

            .crumbs mat-icon {
                font-size: 16px;
                width: 16px;
                height: 16px;
            }

            .spacer {
                flex: 1 1 auto;
            }

            .health {
                display: inline-flex;
                align-items: center;
                gap: 6px;
                font-size: 12px;
                color: var(--app-text-2);
            }

            .health::before {
                content: "";
                width: 7px;
                height: 7px;
                border-radius: 50%;
                background: var(--app-ok);
            }

            .offline {
                display: flex;
                align-items: center;
                gap: 8px;
                padding: 6px 8px 6px 24px;
                background: var(--app-err);
                color: #ffffff;
                font-size: 13px;
                flex: 0 0 auto;
            }

            .offline span {
                flex: 1 1 auto;
            }

            .offline button {
                color: #ffffff;
            }

            .content {
                flex: 1 1 auto;
                min-height: 0;
                overflow-y: auto;
            }

            .scrim {
                display: none;
            }

            @media (max-width: 960px) {
                :host {
                    grid-template-columns: minmax(0, 1fr);
                }

                .sidebar {
                    position: fixed;
                    z-index: 40;
                    top: 0;
                    bottom: 0;
                    left: 0;
                    width: 272px;
                    /* Fixed, it ignores the host's padding: the notch and the home indicator kept free here too. */
                    box-sizing: border-box;
                    padding-top: env(safe-area-inset-top);
                    padding-bottom: env(safe-area-inset-bottom);
                    transform: translateX(-100%);
                    transition: transform 0.2s;
                    box-shadow: var(--app-shadow-2);
                }

                .sidebar.open {
                    transform: none;
                }

                .scrim {
                    display: block;
                    border: 0;
                    padding: 0;
                    position: fixed;
                    inset: 0;
                    z-index: 30;
                    background: rgba(0, 0, 0, 0.35);
                }

                .menu-button {
                    display: inline-flex;
                }

                .topbar {
                    padding: 0 12px;
                }

                .health {
                    display: none;
                }
            }
        `,
    ],
})
export class AppShellComponent {
    private document = inject(DOCUMENT);
    private router = inject(Router);
    private store = inject(Store);

    readonly name = input.required<string>();
    readonly subtitle = input<string | null>(null);
    readonly logoUrl = input<string | null>(null);
    /** Group and entry labels are translation keys. */
    readonly navGroups = input.required<ShellNavGroup[]>();
    readonly userName = input<string>("");
    readonly userRole = input<string>("");
    readonly version = input<string | null>(null);
    readonly versionTooltip = input<string>("");
    /** null until the first health check completes. */
    readonly backendReachable = input<boolean | null>(null);
    /** The tenants where the person has a user - the menu offers the switch with two or more. */
    readonly tenants = input<TenantChoice[]>([]);
    readonly currentTenantId = input<number | null>(null);
    readonly switchTenant = output<TenantChoice>();
    readonly logout = output<void>();
    readonly retryHealth = output<void>();

    readonly schemes = SCHEMES;
    readonly languages = LANGUAGES;
    readonly language = this.store.selectSignal(LanguageSelectors.selectLanguage);
    readonly colorScheme = signal(readColorScheme(this.storage()));
    /** Below 960px: whether the sidebar is slid in. */
    readonly navOpen = signal(false);

    readonly brandInitial = computed(() => this.name().trim().charAt(0).toUpperCase());
    readonly userInitials = computed(() => initials(this.userName()));

    private readonly url = toSignal(
        this.router.events.pipe(
            filter(event => event instanceof NavigationEnd),
            map(() => this.router.url),
            takeUntilDestroyed(),
        ),
        {initialValue: this.router.url},
    );
    /** The menu entry the page belongs to - the top bar's "where am I". */
    readonly current = computed(() => shellNavItem(this.navGroups(), this.url()));

    chooseScheme(scheme: ColorScheme): void {
        this.colorScheme.set(scheme);
        applyColorScheme(this.document.documentElement, scheme);
        storeColorScheme(this.storage(), scheme);
    }

    chooseLanguage(language: string): void {
        this.store.dispatch(LanguageActions.chooseLanguage({language}));
    }

    closeNav(): void {
        this.navOpen.set(false);
    }

    private storage(): Storage | null {
        return localStorageOf(this.document.defaultView);
    }
}
