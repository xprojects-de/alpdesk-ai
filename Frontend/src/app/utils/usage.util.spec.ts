import {UsageRow} from "../models/ai.model";
import {
    billableAmount,
    groupUsageByTenant,
    inputWithCache,
    monthLabel,
    monthOf,
    shiftMonth,
    totalAmount,
    usageTotals,
} from "./usage.util";

describe("usage.util", () => {
    const row = (changes: Partial<UsageRow>): UsageRow => ({
        tenantId: 1,
        tenantName: "Musterfirma GmbH",
        provider: "OPENAI",
        modelName: "gpt-5",
        kind: "CHAT",
        keySource: "OWN",
        calls: 1,
        inputTokens: 100,
        outputTokens: 50,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        estimated: false,
        amount: 0.5,
        ...changes,
    });

    it("computes months across year boundaries", () => {
        expect(monthOf(new Date(2026, 9, 6))).toBe("2026-10");
        expect(shiftMonth("2026-01", -1)).toBe("2025-12");
        expect(shiftMonth("2026-12", 1)).toBe("2027-01");
        expect(monthLabel("2026-10", "de-DE")).toBe("Oktober 2026");
        expect(monthLabel("2026-10", "en-GB")).toBe("October 2026");
    });

    it("bills only the usage on global keys", () => {
        const rows = [
            row({amount: 0.5}),
            row({keySource: "GLOBAL", amount: 1.25}),
            row({keySource: "GLOBAL", amount: 2}),
        ];
        expect(billableAmount(rows)).toBeCloseTo(3.25);
        expect(totalAmount(rows)).toBeCloseTo(3.75);
    });

    it("groups by tenant name with sums per tenant", () => {
        const groups = groupUsageByTenant([
            row({tenantId: 2, tenantName: "Zum Hirschen", amount: 1}),
            row({tenantId: 1, modelName: "text-embedding-3", keySource: "GLOBAL", amount: 2}),
            row({tenantId: 1, provider: "ANTHROPIC", amount: 3}),
        ]);

        expect(groups.map(group => group.tenantName)).toEqual(["Musterfirma GmbH", "Zum Hirschen"]);
        expect(groups[0].rows.map(r => r.provider)).toEqual(["ANTHROPIC", "OPENAI"]);
        expect(groups[0].billable).toBe(2);
        expect(groups[0].total).toBe(5);
    });
});

describe("usageTotals", () => {
    it("adds up calls, tokens in and out, the cost and what of it is billed", () => {
        const row = (calls: number, inputTokens: number, outputTokens: number, amount: number, keySource: string) =>
            ({
                calls,
                inputTokens,
                outputTokens,
                cacheReadTokens: 0,
                cacheWriteTokens: 0,
                amount,
                keySource,
            }) as UsageRow;

        expect(usageTotals([row(3, 100, 50, 1.5, "GLOBAL"), row(2, 10, 5, 0.25, "OWN")])).toEqual({
            calls: 5,
            tokens: 165,
            total: 1.75,
            billable: 1.5,
        });
        expect(usageTotals([])).toEqual({calls: 0, tokens: 0, total: 0, billable: 0});
    });

    it("counts a web search's searches as calls, not as tokens", () => {
        const chat = {
            calls: 1,
            inputTokens: 100,
            outputTokens: 50,
            cacheReadTokens: 0,
            cacheWriteTokens: 0,
            amount: 0.5,
            keySource: "OWN",
            kind: "CHAT",
        } as UsageRow;
        const searches = {
            calls: 3,
            inputTokens: 3,
            outputTokens: 0,
            cacheReadTokens: 0,
            cacheWriteTokens: 0,
            amount: 0.024,
            keySource: "OWN",
            kind: "WEB_SEARCH",
        } as UsageRow;

        expect(usageTotals([chat, searches])).toEqual({calls: 4, tokens: 150, total: 0.524, billable: 0});
    });

    it("counts the cached tokens as tokens that went in - billed apart, but read", () => {
        const cached = {
            calls: 2,
            inputTokens: 40,
            outputTokens: 10,
            cacheReadTokens: 900,
            cacheWriteTokens: 60,
            amount: 0.01,
            keySource: "GLOBAL",
            kind: "CHAT",
        } as UsageRow;

        expect(inputWithCache(cached)).toBe(1000);
        expect(usageTotals([cached]).tokens).toBe(1010);
    });
});
