import {UsageRow} from "../models/ai.model";

/** YYYY-MM of the given day (local time). */
export function monthOf(date: Date): string {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
}

/** The month `delta` months before (negative) or after `month` (YYYY-MM). */
export function shiftMonth(month: string, delta: number): string {
    const [year, monthNumber] = month.split("-").map(Number);
    return monthOf(new Date(year, monthNumber - 1 + delta, 1));
}

/** "Oktober 2026" for "2026-10" in "de-DE", "October 2026" in "en-GB". */
export function monthLabel(month: string, locale: string): string {
    const [year, monthNumber] = month.split("-").map(Number);
    return new Date(year, monthNumber - 1, 1).toLocaleDateString(locale, {month: "long", year: "numeric"});
}

/** What is billed: usage on the admin's global keys. */
export function billableAmount(rows: UsageRow[]): number {
    return rows.filter(row => row.keySource === "GLOBAL").reduce((sum, row) => sum + Number(row.amount), 0);
}

export function totalAmount(rows: UsageRow[]): number {
    return rows.reduce((sum, row) => sum + Number(row.amount), 0);
}

export interface TenantUsage {
    tenantId: number;
    tenantName: string;
    rows: UsageRow[];
    billable: number;
    total: number;
}

/** The rows per tenant (by name), within a tenant by provider, model and key source. */
export function groupUsageByTenant(rows: UsageRow[]): TenantUsage[] {
    const byTenant = new Map<number, UsageRow[]>();
    for (const row of rows) {
        byTenant.set(row.tenantId, [...(byTenant.get(row.tenantId) ?? []), row]);
    }
    const groups: TenantUsage[] = [];
    for (const [tenantId, tenantRows] of byTenant) {
        const sorted = [...tenantRows].sort(
            (a, b) =>
                a.provider.localeCompare(b.provider) ||
                a.modelName.localeCompare(b.modelName) ||
                a.keySource.localeCompare(b.keySource),
        );
        groups.push({
            tenantId,
            tenantName: sorted[0].tenantName,
            rows: sorted,
            billable: billableAmount(sorted),
            total: totalAmount(sorted),
        });
    }
    return groups.sort((a, b) => a.tenantName.localeCompare(b.tenantName));
}

/** Everything that went in - cached or not: the provider bills the cached part at its own price, but it was read. */
export function inputWithCache(row: Pick<UsageRow, "inputTokens" | "cacheReadTokens" | "cacheWriteTokens">): number {
    return row.inputTokens + row.cacheReadTokens + row.cacheWriteTokens;
}

/**
 * One tenant's month at a glance: calls, tokens (in, cached ones included, and out), what it cost and what of it is
 * billed. A web search keeps its searches where a model keeps tokens - they are calls, not tokens.
 */
export interface UsageTotals {
    calls: number;
    tokens: number;
    total: number;
    billable: number;
}

export function usageTotals(rows: UsageRow[]): UsageTotals {
    return {
        calls: rows.reduce((sum, row) => sum + row.calls, 0),
        tokens: rows
            .filter(row => row.kind !== "WEB_SEARCH")
            .reduce((sum, row) => sum + inputWithCache(row) + row.outputTokens, 0),
        total: totalAmount(rows),
        billable: billableAmount(rows),
    };
}
