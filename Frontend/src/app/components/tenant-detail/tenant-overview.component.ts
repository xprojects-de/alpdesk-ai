import {Component, computed, inject, input, output} from "@angular/core";
import {Store} from "@ngrx/store";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {Tenant} from "../../models/tenant.model";
import * as TenantActions from "../../store/tenant/tenant.actions";
import * as TenantSelectors from "../../store/tenant/tenant.selectors";
import * as UsageActions from "../../store/usage/usage.actions";
import * as UsageSelectors from "../../store/usage/usage.selectors";
import {codeExecutionLabel, sandboxStatusChip} from "../../utils/tenant.util";
import {monthLabel, monthOf, usageTotals} from "../../utils/usage.util";
import {TenantModelsSummaryComponent} from "./tenant-models-summary.component";
import {TranslocoPipe} from "@jsverse/transloco";
import {TranslocoDatePipe, TranslocoDecimalPipe} from "@jsverse/transloco-locale";
import {injectLocale} from "../../i18n/locale";

/** The first tab of the admin's tenant page: this month's usage, the master data and the models at a glance. */
@Component({
    selector: "app-tenant-overview",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        TranslocoDecimalPipe,
        MatButtonModule,
        MatIconModule,
        TenantModelsSummaryComponent,
    ],
    template: `
        <div class="stats">
            <div class="stat">
                <div class="label">
                    <mat-icon>forum</mat-icon>{{ "tenantDetail.overview.calls" | transloco: {month: monthName()} }}
                </div>
                <div class="value">{{ totals().calls | translocoDecimal: {maximumFractionDigits: 0} }}</div>
                <div class="note">{{ "tenantDetail.overview.callsNote" | transloco }}</div>
            </div>
            <div class="stat">
                <div class="label"><mat-icon>token</mat-icon>{{ "tenantDetail.overview.tokens" | transloco }}</div>
                <div class="value">{{ totals().tokens | translocoDecimal: {maximumFractionDigits: 0} }}</div>
                <div class="note">{{ "tenantDetail.overview.tokensNote" | transloco }}</div>
            </div>
            <div class="stat">
                <div class="label"><mat-icon>euro</mat-icon>{{ "tenantDetail.overview.cost" | transloco }}</div>
                <div class="value">
                    {{
                        "tenantDetail.overview.amount" | transloco: {amount: (totals().total | translocoDecimal: money)}
                    }}
                </div>
                <div class="note">{{ "tenantDetail.overview.costNote" | transloco }}</div>
            </div>
            <div class="stat">
                <div class="label">
                    <mat-icon>receipt_long</mat-icon>{{ "tenantDetail.overview.billable" | transloco }}
                </div>
                <div class="value">
                    {{
                        "tenantDetail.overview.amount"
                            | transloco: {amount: (totals().billable | translocoDecimal: money)}
                    }}
                </div>
                <div class="note">{{ "tenantDetail.overview.billableNote" | transloco }}</div>
            </div>
        </div>

        <div class="columns">
            <div class="app-panel">
                <div class="app-panel-head">
                    <span class="app-panel-title">{{ "tenantDetail.overview.masterData" | transloco }}</span>
                    <span class="spacer"></span>
                    <button mat-stroked-button (click)="editTenant.emit()">
                        <mat-icon>edit</mat-icon>
                        {{ "common.edit" | transloco }}
                    </button>
                </div>
                <dl>
                    <dt>{{ "tenantDetail.overview.status" | transloco }}</dt>
                    <dd>
                        <span class="app-chip" [class.ok]="tenant().active">
                            {{ (tenant().active ? "common.active" : "common.inactive") | transloco }}
                        </span>
                    </dd>
                    <dt>{{ "tenantDetail.overview.contact" | transloco }}</dt>
                    <dd>{{ tenant().contactEmail ?? "–" }}</dd>
                    <dt>{{ "tenantDetail.overview.globalKeys" | transloco }}</dt>
                    <dd>
                        {{
                            (tenant().globalKeysAllowed
                                ? "tenantDetail.overview.globalKeysAllowed"
                                : "tenantDetail.overview.globalKeysNotAllowed"
                            ) | transloco
                        }}
                    </dd>
                    <dt>{{ "tenantDetail.overview.codeExecutionLabel" | transloco }}</dt>
                    <dd>
                        <div class="sandbox-line">
                            {{ codeExecution().key | transloco: codeExecution().params }}
                            @if (tenant().codeExecution) {
                                <button
                                    mat-button
                                    class="check"
                                    [disabled]="sandboxCheck()?.loading"
                                    (click)="checkSandbox()"
                                >
                                    <mat-icon>network_check</mat-icon>
                                    {{ "tenantDetail.overview.checkSandbox" | transloco }}
                                </button>
                            }
                        </div>
                        @for (instance of sandboxInstances(); track instance.url) {
                            <div class="instance">
                                <span class="mono">{{ instance.url }}</span>
                                @if (instance.chip) {
                                    <span class="app-chip" [class]="instance.chip.tone">{{
                                        instance.chip.label | transloco
                                    }}</span>
                                }
                                @if (instance.video) {
                                    <span class="app-tag">{{ "tenantDetail.overview.sandboxVideo" | transloco }}</span>
                                }
                                @if (instance.extras) {
                                    <span class="app-tag">{{ "tenantDetail.overview.sandboxExtras" | transloco }}</span>
                                }
                            </div>
                        }
                        @if (sandboxCheck()?.extrasAgree === false) {
                            <p class="app-note warn extras-differ">
                                <mat-icon>warning</mat-icon>
                                {{ "tenantDetail.overview.sandboxExtrasDiffer" | transloco }}
                            </p>
                        }
                    </dd>
                    <dt>{{ "tenantDetail.overview.database" | transloco }}</dt>
                    <dd class="mono">{{ tenant().databaseName }}</dd>
                    <dt>{{ "tenantDetail.overview.created" | transloco }}</dt>
                    <dd class="num">{{ tenant().createdAt | translocoDate: {dateStyle: "medium"} }}</dd>
                    @if (tenant().notes) {
                        <dt>{{ "tenantDetail.overview.notes" | transloco }}</dt>
                        <dd class="notes">{{ tenant().notes }}</dd>
                    }
                </dl>
            </div>
            <app-tenant-models-summary [tenant]="tenant()" [compact]="true" (edit)="editModels.emit()" />
        </div>
    `,
    styles: [
        `
            :host {
                display: flex;
                flex-direction: column;
                gap: 16px;
            }

            .stats {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
                gap: 12px;
            }

            .stat {
                background: var(--app-surface);
                border: 1px solid var(--app-border);
                border-radius: 10px;
                padding: 14px 16px;
            }

            .label {
                display: flex;
                align-items: center;
                gap: 6px;
                font-size: 12.5px;
                color: var(--app-text-2);
            }

            .label mat-icon {
                font-size: 17px;
                width: 17px;
                height: 17px;
                color: var(--app-text-3);
            }

            .value {
                font-size: 24px;
                font-weight: 650;
                letter-spacing: -0.02em;
                margin-top: 4px;
                font-variant-numeric: tabular-nums;
            }

            .note {
                font-size: 12px;
                color: var(--app-text-3);
            }

            .columns {
                display: grid;
                grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
                gap: 16px;
                align-items: start;
            }

            dl {
                display: grid;
                grid-template-columns: max-content minmax(0, 1fr);
                gap: 10px 24px;
                margin: 0;
                padding: 16px;
            }

            dt {
                color: var(--app-text-2);
            }

            dd {
                margin: 0;
                word-break: break-word;
            }

            .notes {
                white-space: pre-wrap;
            }

            .sandbox-line {
                display: flex;
                align-items: center;
                flex-wrap: wrap;
                gap: 4px 12px;
            }

            .instance {
                display: flex;
                align-items: center;
                flex-wrap: wrap;
                gap: 4px 8px;
                margin-top: 6px;
                font-size: 12.5px;
            }

            .instance .mono {
                word-break: break-all;
            }

            .extras-differ {
                margin: 8px 0 0;
            }

            @media (max-width: 960px) {
                .columns {
                    grid-template-columns: minmax(0, 1fr);
                }
            }
        `,
    ],
})
export class TenantOverviewComponent {
    private store = inject(Store);

    readonly tenant = input.required<Tenant>();
    readonly editTenant = output<void>();
    readonly editModels = output<void>();

    private readonly locale = injectLocale();
    private readonly month = monthOf(new Date());
    /** The month's name alone, in the active language. */
    readonly monthName = computed(() => monthLabel(this.month, this.locale()).split(" ")[0]);
    readonly money = {minimumFractionDigits: 2, maximumFractionDigits: 2};
    private readonly rows = this.store.selectSignal(UsageSelectors.selectUsageRows);
    private readonly loadedMonth = this.store.selectSignal(UsageSelectors.selectUsageMonth);
    /** This month's usage of this tenant, from the admin's usage of all tenants. */
    readonly totals = computed(() =>
        usageTotals(
            this.loadedMonth() === this.month ? this.rows().filter(row => row.tenantId === this.tenant().id) : [],
        ),
    );

    readonly codeExecution = computed(() => codeExecutionLabel(this.tenant()));
    private readonly lastSandboxCheck = this.store.selectSignal(TenantSelectors.selectSandboxCheck);
    /** The last check, if it was this tenant's. */
    readonly sandboxCheck = computed(() => {
        const check = this.lastSandboxCheck();
        return check?.tenantId === this.tenant().id ? check : null;
    });
    /** The tenant's instances, each with what the last check found - no chip before a check. */
    readonly sandboxInstances = computed(() => {
        const checked = this.sandboxCheck()?.instances ?? null;
        const urls = this.tenant().sandboxUrls?.split(",") ?? [];
        return urls.map(url => {
            const found = checked?.find(instance => instance.url === url);
            return {
                url,
                chip: found ? sandboxStatusChip(found.status) : null,
                video: found?.video ?? false,
                extras: found?.extras ?? false,
            };
        });
    });

    constructor() {
        this.store.dispatch(UsageActions.loadUsage({scope: "admin", month: this.month}));
    }

    checkSandbox(): void {
        this.store.dispatch(TenantActions.checkSandbox({tenantId: this.tenant().id}));
    }
}
