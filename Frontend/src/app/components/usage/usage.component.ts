import {Component, computed, inject} from "@angular/core";
import {ActivatedRoute} from "@angular/router";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {TranslocoCurrencyPipe, TranslocoDecimalPipe} from "@jsverse/transloco-locale";
import {MatTableModule} from "@angular/material/table";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {KEY_SOURCE_LABELS, KIND_LABELS, PROVIDER_LABELS} from "../../models/ai.model";
import {UsageScope} from "../../services/usage.service";
import * as UsageActions from "../../store/usage/usage.actions";
import * as UsageSelectors from "../../store/usage/usage.selectors";
import {
    billableAmount,
    groupUsageByTenant,
    inputWithCache,
    monthLabel,
    monthOf,
    shiftMonth,
    totalAmount,
} from "../../utils/usage.util";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {injectLocale} from "../../i18n/locale";

/**
 * The AI usage of a month: in the dashboard every tenant's (route data scope "admin"), grouped by tenant
 * with what is to be billed; in the tenant area the own tenant's (scope "tenant").
 */
@Component({
    selector: "app-usage",
    imports: [
        TranslocoPipe,
        TranslocoDecimalPipe,
        TranslocoCurrencyPipe,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatProgressBarModule,
        MatTooltipModule,
        PageHeaderComponent,
        EmptyStateComponent,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'usage.title' | transloco"
                [subtitle]="(scope === 'admin' ? 'usage.subtitle.admin' : 'usage.subtitle.tenant') | transloco"
            >
                <div class="month-picker">
                    <button mat-icon-button (click)="showMonth(-1)" [matTooltip]="'usage.previousMonth' | transloco">
                        <mat-icon>chevron_left</mat-icon>
                    </button>
                    <span class="month">{{ label() }}</span>
                    <button mat-icon-button (click)="showMonth(1)" [matTooltip]="'usage.nextMonth' | transloco">
                        <mat-icon>chevron_right</mat-icon>
                    </button>
                </div>
            </app-page-header>

            <div class="tiles">
                <div class="tile">
                    <div class="tile-label"><mat-icon>payments</mat-icon>{{ "usage.tiles.total" | transloco }}</div>
                    <div class="tile-value num">{{ total() | translocoCurrency: "symbol" : twoDigits : "EUR" }}</div>
                    <div class="tile-note">{{ "usage.tiles.totalNote" | transloco }}</div>
                </div>
                <div class="tile">
                    <div class="tile-label"><mat-icon>receipt_long</mat-icon>{{ billableKey | transloco }}</div>
                    <div class="tile-value num">
                        {{ billableTotal() | translocoCurrency: "symbol" : twoDigits : "EUR" }}
                    </div>
                    <div class="tile-note">{{ "usage.tiles.billableNote" | transloco }}</div>
                </div>
                <div class="tile">
                    <div class="tile-label"><mat-icon>key</mat-icon>{{ "usage.tiles.ownKeys" | transloco }}</div>
                    <div class="tile-value num">
                        {{ total() - billableTotal() | translocoCurrency: "symbol" : twoDigits : "EUR" }}
                    </div>
                    <div class="tile-note">
                        {{
                            (scope === "admin" ? "usage.tiles.ownKeysNoteAdmin" : "usage.tiles.ownKeysNoteTenant")
                                | transloco
                        }}
                    </div>
                </div>
                @if (scope === "admin") {
                    <div class="tile">
                        <div class="tile-label">
                            <mat-icon>apartment</mat-icon>{{ "usage.tiles.tenants" | transloco }}
                        </div>
                        <div class="tile-value num">{{ groups().length | translocoDecimal }}</div>
                        <div class="tile-note">{{ "usage.tiles.tenantsNote" | transloco }}</div>
                    </div>
                }
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @for (group of groups(); track group.tenantId) {
                <section class="app-panel">
                    <div class="app-panel-head">
                        <span class="app-panel-title">{{
                            scope === "admin" ? group.tenantName : ("usage.byModel" | transloco)
                        }}</span>
                    </div>
                    <div class="table-container">
                        <table mat-table [dataSource]="group.rows" class="usage-table">
                            <ng-container matColumnDef="model">
                                <th mat-header-cell *matHeaderCellDef>{{ "usage.columns.model" | transloco }}</th>
                                <td mat-cell *matCellDef="let row">
                                    <div class="cell-main">
                                        <div>
                                            <div class="cell-title">{{ row.modelName }}</div>
                                            <div class="cell-sub">
                                                {{ providerLabels[row.provider] | transloco }} ·
                                                {{ kindLabels[row.kind] | transloco }}
                                            </div>
                                        </div>
                                    </div>
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="keySource">
                                <th mat-header-cell *matHeaderCellDef>{{ "usage.columns.key" | transloco }}</th>
                                <td mat-cell *matCellDef="let row">
                                    <span class="app-tag">{{ keySourceLabels[row.keySource] | transloco }}</span>
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="calls">
                                <th mat-header-cell *matHeaderCellDef class="number">
                                    {{ "usage.columns.calls" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let row" class="number num">
                                    {{ row.calls | translocoDecimal }}
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="tokens">
                                <th mat-header-cell *matHeaderCellDef class="number">
                                    {{ "usage.columns.tokens" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let row" class="number num">
                                    @if (row.kind === "WEB_SEARCH") {
                                        –
                                    } @else {
                                        @if (row.estimated) {
                                            <span class="estimated" [matTooltip]="'usage.estimated' | transloco"
                                                >~</span
                                            >
                                        }
                                        {{ inputWithCache(row) | translocoDecimal }} /
                                        {{ row.outputTokens | translocoDecimal }}
                                        @if (row.cacheReadTokens + row.cacheWriteTokens > 0) {
                                            <div class="cached">
                                                {{
                                                    "usage.cached"
                                                        | transloco
                                                            : {
                                                                  read: row.cacheReadTokens | translocoDecimal,
                                                                  write: row.cacheWriteTokens | translocoDecimal,
                                                              }
                                                }}
                                            </div>
                                        }
                                    }
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="amount">
                                <th mat-header-cell *matHeaderCellDef class="number">
                                    {{ "usage.columns.amount" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let row" class="number num">
                                    {{ row.estimated ? "~" : ""
                                    }}{{ row.amount | translocoCurrency: "symbol" : upToFourDigits : "EUR" }}
                                </td>
                            </ng-container>
                            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                            <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                        </table>
                    </div>
                    <div class="sums num">
                        <span>{{
                            "usage.sums.total"
                                | transloco
                                    : {amount: group.total | translocoCurrency: "symbol" : upToFourDigits : "EUR"}
                        }}</span>
                        <strong>{{
                            "usage.sums.billable"
                                | transloco
                                    : {
                                          label: billableKey | transloco,
                                          amount: group.billable | translocoCurrency: "symbol" : upToFourDigits : "EUR",
                                      }
                        }}</strong>
                    </div>
                </section>
            } @empty {
                @if (!loading()) {
                    <div class="app-panel">
                        <app-empty-state
                            icon="insights"
                            [title]="'usage.empty.title' | transloco"
                            [text]="'usage.empty.text' | transloco"
                        />
                    </div>
                }
            }
        </div>
    `,
    styles: [
        `
            .month-picker {
                display: flex;
                align-items: center;
                gap: 4px;
                border: 1px solid var(--app-border);
                border-radius: 10px;
                background: var(--app-surface);
            }

            .month {
                min-width: 140px;
                text-align: center;
                font-weight: 500;
            }

            .tiles {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                gap: 12px;
            }

            .tile {
                border: 1px solid var(--app-border);
                border-radius: 10px;
                background: var(--app-surface);
                padding: 14px 16px;
            }

            .tile-label {
                display: flex;
                align-items: center;
                gap: 6px;
                color: var(--app-text-2);
                font-size: 13px;
            }

            .tile-label mat-icon {
                font-size: 16px;
                width: 16px;
                height: 16px;
                color: var(--app-brand-text);
            }

            .tile-value {
                font-size: 24px;
                font-weight: 650;
                margin-top: 6px;
                letter-spacing: -0.01em;
            }

            .tile-note {
                font-size: 12px;
                color: var(--app-text-3);
                margin-top: 2px;
            }

            .usage-table {
                width: 100%;
            }

            .number {
                text-align: right;
            }

            .estimated {
                cursor: help;
                margin-right: 2px;
            }

            .cached {
                font-size: 12px;
                color: var(--app-text-2);
            }

            .sums {
                display: flex;
                flex-wrap: wrap;
                justify-content: flex-end;
                gap: 8px 24px;
                padding: 10px 16px;
                color: var(--app-text-2);
                background: var(--app-surface-2);
                border-radius: 0 0 10px 10px;
            }

            .sums strong {
                color: var(--app-text);
                font-weight: 600;
            }
        `,
    ],
})
export class UsageComponent {
    private store = inject(Store);

    readonly scope: UsageScope = inject(ActivatedRoute).snapshot.data["scope"];
    // Keyed by string: table rows (matCellDef) are untyped in the template.
    readonly providerLabels: Record<string, string> = PROVIDER_LABELS;
    // Keyed by string: table rows (matCellDef) are untyped in the template.
    readonly kindLabels: Record<string, string> = KIND_LABELS;
    // Keyed by string: table rows (matCellDef) are untyped in the template.
    readonly keySourceLabels: Record<string, string> = KEY_SOURCE_LABELS;
    readonly displayedColumns = ["model", "keySource", "calls", "tokens", "amount"];
    readonly inputWithCache = inputWithCache;
    readonly billableKey = this.scope === "admin" ? "usage.billable.admin" : "usage.billable.tenant";
    readonly twoDigits = {minimumFractionDigits: 2, maximumFractionDigits: 2};
    readonly upToFourDigits = {minimumFractionDigits: 2, maximumFractionDigits: 4};
    private readonly locale = injectLocale();

    private readonly month = this.store.selectSignal(UsageSelectors.selectUsageMonth);
    private readonly rows = this.store.selectSignal(UsageSelectors.selectUsageRows);
    readonly loading = this.store.selectSignal(UsageSelectors.selectUsageLoading);

    readonly label = computed(() => monthLabel(this.month() ?? monthOf(new Date()), this.locale()));
    readonly groups = computed(() => groupUsageByTenant(this.rows()));
    readonly billableTotal = computed(() => billableAmount(this.rows()));
    readonly total = computed(() => totalAmount(this.rows()));

    constructor() {
        // Back on the page, the month chosen before stays.
        this.load(this.month() ?? monthOf(new Date()));
    }

    showMonth(delta: number): void {
        this.load(shiftMonth(this.month() ?? monthOf(new Date()), delta));
    }

    private load(month: string): void {
        this.store.dispatch(UsageActions.loadUsage({scope: this.scope, month}));
    }
}
