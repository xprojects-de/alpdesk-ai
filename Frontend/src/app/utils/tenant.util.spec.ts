import {FormControl, FormGroup} from "@angular/forms";
import {Tenant} from "../models/tenant.model";
import {
    buildTenantRequest,
    codeExecutionLabel,
    sandboxAddresses,
    sandboxComplete,
    sandboxStatusChip,
    sandboxToken,
    TenantFormValue,
} from "./tenant.util";

const TOKEN = "t".repeat(36) + "wxyz";

const tenant = (sandboxUrls: string | null): Tenant => ({
    id: 7,
    name: "Musterfirma",
    active: true,
    contactEmail: null,
    notes: null,
    databaseName: "tenant_ab12",
    globalKeysAllowed: false,
    tasksAllowed: false,
    a2aAllowed: false,
    codeExecution: sandboxUrls !== null,
    sandboxUrls,
    sandboxTokenHint: sandboxUrls !== null ? "…wxyz" : null,
    embeddingModelId: null,
    rerankingModelId: null,
    searchLanguage: "SIMPLE",
    createdAt: "2026-10-11T08:00:00Z",
    updatedAt: "2026-10-11T08:00:00Z",
});

const form = (sandboxUrls: string, token: string): TenantFormValue => ({
    name: "Musterfirma",
    contactEmail: " ",
    notes: "Notiz",
    active: true,
    globalKeysAllowed: false,
    tasksAllowed: true,
    a2aAllowed: true,
    sandboxUrls,
    sandboxToken: token,
    searchLanguage: "ENGLISH",
});

describe("sandboxAddresses", () => {
    it("takes one address per line or comma separated, trimmed, without empty ones", () => {
        expect(sandboxAddresses(" http://a:8080 \n\nhttp://b:8080, http://c:8080 ,")).toBe(
            "http://a:8080,http://b:8080,http://c:8080",
        );
        expect(sandboxAddresses(" \n , ")).toBe("");
    });
});

describe("buildTenantRequest", () => {
    it("sends the addresses and the token of a new sandbox", () => {
        expect(buildTenantRequest(form("http://a:8080\nhttp://b:8080", ` ${TOKEN} `), null)).toEqual({
            name: "Musterfirma",
            active: true,
            globalKeysAllowed: false,
            tasksAllowed: true,
            a2aAllowed: true,
            contactEmail: null,
            notes: "Notiz",
            sandboxUrls: "http://a:8080,http://b:8080",
            sandboxToken: TOKEN,
            searchLanguage: "ENGLISH",
        });
    });

    it("sends the search language with a new tenant only - a change has its own call", () => {
        expect(buildTenantRequest(form("", ""), tenant(null)).searchLanguage).toBeNull();
    });

    it("leaves both out for a new tenant without a sandbox", () => {
        const request = buildTenantRequest(form("", ""), null);
        expect(request.sandboxUrls).toBeNull();
        expect(request.sandboxToken).toBeNull();
    });

    it("leaves an empty token out on edit, so the stored one stays", () => {
        const request = buildTenantRequest(form("http://a:8080", ""), tenant("http://a:8080"));
        expect(request.sandboxUrls).toBe("http://a:8080");
        expect(request.sandboxToken).toBeNull();
    });

    it("removes the sandbox when its addresses are emptied on edit", () => {
        expect(buildTenantRequest(form(" ", ""), tenant("http://a:8080")).sandboxUrls).toBe("");
        expect(buildTenantRequest(form("", ""), tenant(null)).sandboxUrls).toBeNull();
    });
});

describe("sandboxToken", () => {
    const valid = (value: string) => sandboxToken()(new FormControl(value)) === null;

    it("takes an empty token (unchanged) or one of 32 to 200 characters without spaces", () => {
        expect(valid("")).toBe(true);
        expect(valid("x".repeat(32))).toBe(true);
        expect(valid("x".repeat(200))).toBe(true);
        expect(valid("x".repeat(31))).toBe(false);
        expect(valid("x".repeat(201))).toBe(false);
        expect(valid("x".repeat(20) + " " + "x".repeat(20))).toBe(false);
    });
});

describe("sandboxComplete", () => {
    const errors = (urls: string, token: string, stored: boolean) =>
        sandboxComplete(stored)(
            new FormGroup({sandboxUrls: new FormControl(urls), sandboxToken: new FormControl(token)}),
        );

    it("wants a token for addresses - typed in or stored - and addresses for a token", () => {
        expect(errors("http://a:8080", "", false)).toEqual({sandboxTokenMissing: true});
        expect(errors("http://a:8080", "", true)).toBeNull();
        expect(errors("http://a:8080", TOKEN, false)).toBeNull();
        expect(errors("", TOKEN, true)).toEqual({sandboxAddressesMissing: true});
        expect(errors("", "", true)).toBeNull();
    });
});

describe("codeExecutionLabel", () => {
    it("counts the tenant's instances", () => {
        expect(codeExecutionLabel(tenant("http://a:8080"))).toEqual({
            key: "tenantDetail.overview.codeExecution.one",
            params: {count: 1},
        });
        expect(codeExecutionLabel(tenant("http://a:8080,http://b:8080"))).toEqual({
            key: "tenantDetail.overview.codeExecution.other",
            params: {count: 2},
        });
        expect(codeExecutionLabel(tenant(null))).toEqual({key: "tenantDetail.overview.codeExecution.off", params: {}});
    });
});

describe("sandboxStatusChip", () => {
    it("labels every status of the check", () => {
        expect(sandboxStatusChip("reachable")).toEqual({
            label: "tenantDetail.overview.sandboxStatus.reachable",
            tone: "ok",
        });
        expect(sandboxStatusChip("busy")).toEqual({label: "tenantDetail.overview.sandboxStatus.busy", tone: "warn"});
        expect(sandboxStatusChip("token-refused")).toEqual({
            label: "tenantDetail.overview.sandboxStatus.tokenRefused",
            tone: "err",
        });
        expect(sandboxStatusChip("unreachable")).toEqual({
            label: "tenantDetail.overview.sandboxStatus.unreachable",
            tone: "err",
        });
        expect(sandboxStatusChip("unusable")).toEqual({
            label: "tenantDetail.overview.sandboxStatus.unusable",
            tone: "err",
        });
    });
});
