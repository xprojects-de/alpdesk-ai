import {Component, computed, DestroyRef, effect, inject, input, signal} from "@angular/core";
import {Router} from "@angular/router";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {MatButtonModule} from "@angular/material/button";
import {MatButtonToggleModule} from "@angular/material/button-toggle";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {ROLE_TENANT_ADMIN} from "../../models/auth.model";
import {DocSite} from "../../models/doc-site.model";
import * as AuthSelectors from "../../store/auth/auth.selectors";
import * as SiteActions from "../../store/doc-site/doc-site.actions";
import * as SiteSelectors from "../../store/doc-site/doc-site.selectors";
import {ADDRESS_CHECK_MS, addressDue, readerSiteLabel} from "../../utils/doc-site.util";
import {AreaText} from "../../utils/knowledge-area.util";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {DocSitesPanelComponent} from "../shared/doc-sites-panel/doc-sites-panel.component";
import {DocSiteFrameComponent} from "../shared/doc-sites-panel/doc-site-frame.component";

/**
 * The handbook (/app/handbook/:site): the websites the user reads - the whole tenant's and those of its knowledge
 * areas -, one at a time in an iframe; the website shown is part of the address. A tenant admin keeps them here too.
 */
@Component({
    selector: "app-handbook-page",
    imports: [
        TranslocoPipe,
        MatButtonModule,
        MatButtonToggleModule,
        MatIconModule,
        MatProgressBarModule,
        PageHeaderComponent,
        EmptyStateComponent,
        DocSitesPanelComponent,
        DocSiteFrameComponent,
    ],
    template: `
        <div class="page handbook">
            <app-page-header [title]="'handbook.title' | transloco" [subtitle]="'handbook.subtitle' | transloco">
                @if (tenantAdmin()) {
                    <button mat-stroked-button (click)="managing.set(!managing())">
                        <mat-icon>{{ managing() ? "expand_less" : "settings" }}</mat-icon>
                        {{ (managing() ? "handbook.hideManage" : "handbook.manage") | transloco }}
                    </button>
                }
            </app-page-header>

            @if (managing()) {
                <app-doc-sites-panel scope="self" />
            }

            @if (!loaded()) {
                <mat-progress-bar mode="indeterminate" />
            } @else if (failed() && sites().length === 0) {
                <app-empty-state
                    icon="cloud_off"
                    [title]="'handbook.failed.title' | transloco"
                    [text]="'handbook.failed.text' | transloco"
                >
                    <button mat-stroked-button (click)="loadAgain()">
                        <mat-icon>refresh</mat-icon>
                        {{ "handbook.failed.tryAgain" | transloco }}
                    </button>
                </app-empty-state>
            } @else if (sites().length === 0) {
                <app-empty-state
                    icon="auto_stories"
                    [title]="'handbook.empty.title' | transloco"
                    [text]="(tenantAdmin() ? 'handbook.empty.textAdmin' : 'handbook.empty.text') | transloco"
                />
            } @else {
                @if (sites().length > 1) {
                    <mat-button-toggle-group
                        class="choice"
                        [value]="shown()?.id"
                        (change)="show($event.value)"
                        [attr.aria-label]="'handbook.choose' | transloco"
                    >
                        @for (site of sites(); track site.id) {
                            @let label = labelOf(site);
                            <mat-button-toggle [value]="site.id">{{
                                label.key | transloco: label.params
                            }}</mat-button-toggle>
                        }
                    </mat-button-toggle-group>
                }
                <div class="app-panel viewer">
                    @if (opened(); as site) {
                        @if (site.siteId === shown()?.id) {
                            <app-doc-site-frame class="frame" [url]="site.url" [title]="'handbook.title' | transloco" />
                        }
                    } @else if (openFailed()) {
                        <app-empty-state
                            icon="cloud_off"
                            [title]="'handbook.failed.title' | transloco"
                            [text]="'handbook.failed.siteText' | transloco"
                        >
                            <button mat-stroked-button (click)="openAgain()">
                                <mat-icon>refresh</mat-icon>
                                {{ "handbook.failed.tryAgain" | transloco }}
                            </button>
                        </app-empty-state>
                    } @else {
                        <mat-progress-bar mode="indeterminate" />
                    }
                </div>
            }
        </div>
    `,
    styles: [
        `
            /* The whole width and the window's height below the top bar: the website gets all the room there is. */
            .handbook {
                max-width: none;
                min-height: calc(100vh - 56px);
                padding-bottom: 24px;
                box-sizing: border-box;
            }

            .choice {
                align-self: flex-start;
                flex-wrap: wrap;
            }

            .viewer {
                flex: 1 1 auto;
                display: flex;
                flex-direction: column;
                padding: 0;
                overflow: hidden;
                min-height: 480px;
            }

            .frame {
                flex: 1 1 auto;
            }
        `,
    ],
})
export class HandbookPageComponent {
    private store = inject(Store);
    private router = inject(Router);
    /** The website's id from the address; left out: the first one. */
    readonly site = input<string>();

    readonly sites = this.store.selectSignal(SiteSelectors.selectHandbook);
    readonly loaded = this.store.selectSignal(SiteSelectors.selectHandbookLoaded);
    readonly failed = this.store.selectSignal(SiteSelectors.selectHandbookFailed);
    readonly opened = this.store.selectSignal(SiteSelectors.selectOpenedSite);
    readonly openFailed = this.store.selectSignal(SiteSelectors.selectOpenFailed);
    private readonly roles = this.store.selectSignal(AuthSelectors.selectAuthRoles);
    readonly tenantAdmin = computed(() => this.roles().includes(ROLE_TENANT_ADMIN));
    readonly managing = signal(false);

    /** An unknown website in the address shows the first one. */
    readonly shown = computed<DocSite | null>(() => {
        const sites = this.sites();
        return sites.find(site => String(site.id) === this.site()) ?? sites[0] ?? null;
    });

    constructor() {
        this.store.dispatch(SiteActions.loadHandbook());
        // A fresh address every time a website is shown - each one holds for an hour.
        effect(() => {
            const site = this.shown();
            if (site) {
                this.store.dispatch(SiteActions.openSite({scope: null, id: site.id}));
            }
        });
        // Renewed by the clock, looked at every minute and when the tab is shown again: a timer over the whole hour
        // would stand still while the computer sleeps and renew the address only long after it expired.
        effect(onCleanup => {
            const opened = this.opened();
            if (!opened) {
                return;
            }
            const openedAt = Date.now();
            const renewIfDue = () => {
                if (addressDue(openedAt, Date.now())) {
                    this.store.dispatch(SiteActions.openSite({scope: null, id: opened.siteId}));
                }
            };
            const check = setInterval(renewIfDue, ADDRESS_CHECK_MS);
            document.addEventListener("visibilitychange", renewIfDue);
            onCleanup(() => {
                clearInterval(check);
                document.removeEventListener("visibilitychange", renewIfDue);
            });
        });
        inject(DestroyRef).onDestroy(() => this.store.dispatch(SiteActions.closeSite({scope: null})));
    }

    labelOf(site: DocSite): AreaText {
        return readerSiteLabel(site);
    }

    loadAgain(): void {
        this.store.dispatch(SiteActions.loadHandbook());
    }

    openAgain(): void {
        const site = this.shown();
        if (site) {
            this.store.dispatch(SiteActions.openSite({scope: null, id: site.id}));
        }
    }

    show(id: number): void {
        void this.router.navigate(["/app/handbook", id]);
    }
}
