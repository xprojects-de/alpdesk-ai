import {AbstractControl, ValidationErrors, ValidatorFn} from "@angular/forms";
import {SandboxStatus, SearchLanguage, Tenant, TenantRequest} from "../models/tenant.model";

/** The sandbox's own rule: a shorter token could be guessed. */
export const MIN_SANDBOX_TOKEN = 32;
export const MAX_SANDBOX_TOKEN = 200;

export interface TenantFormValue {
    name: string;
    contactEmail: string;
    notes: string;
    active: boolean;
    globalKeysAllowed: boolean;
    tasksAllowed: boolean;
    a2aAllowed: boolean;
    /** One address per line or comma separated. */
    sandboxUrls: string;
    sandboxToken: string;
    searchLanguage: SearchLanguage;
}

/** The addresses as the backend takes them: comma separated, trimmed, empty ones dropped; "" for none. */
export function sandboxAddresses(text: string): string {
    return text
        .split(/[\n,]/)
        .map(address => address.trim())
        .filter(address => address !== "")
        .join(",");
}

/**
 * The request of the tenant dialog. The sandbox: addresses emptied on edit remove it with its token (""); addresses
 * left empty on create, or never set, are left out (null); an empty token is left out, so the stored one stays. The
 * search language goes with a new tenant only - a change has its own call, since it builds the search again.
 */
export function buildTenantRequest(value: TenantFormValue, existing: Tenant | null): TenantRequest {
    const addresses = sandboxAddresses(value.sandboxUrls);
    const token = value.sandboxToken.trim();
    return {
        name: value.name,
        active: value.active,
        globalKeysAllowed: value.globalKeysAllowed,
        tasksAllowed: value.tasksAllowed,
        a2aAllowed: value.a2aAllowed,
        contactEmail: value.contactEmail.trim() ? value.contactEmail : null,
        notes: value.notes.trim() ? value.notes : null,
        sandboxUrls: addresses !== "" ? addresses : existing?.sandboxUrls ? "" : null,
        sandboxToken: token !== "" ? token : null,
        searchLanguage: existing ? null : value.searchLanguage,
    };
}

/** A token typed in: 32 to 200 characters, no spaces - as the backend checks. Empty is fine - it keeps the stored one. */
export function sandboxToken(): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
        const value = String(control.value ?? "").trim();
        if (value === "") {
            return null;
        }
        return value.length < MIN_SANDBOX_TOKEN || value.length > MAX_SANDBOX_TOKEN || /\s/.test(value)
            ? {sandboxToken: true}
            : null;
    };
}

/**
 * On the form: addresses and token come together - addresses need a token typed in or stored, a token needs
 * addresses (emptied addresses remove the stored token).
 */
export function sandboxComplete(hasStoredToken: boolean): ValidatorFn {
    return (group: AbstractControl): ValidationErrors | null => {
        const addresses = sandboxAddresses(String(group.get("sandboxUrls")?.value ?? ""));
        const token = String(group.get("sandboxToken")?.value ?? "").trim();
        if (addresses !== "" && token === "" && !hasStoredToken) {
            return {sandboxTokenMissing: true};
        }
        if (addresses === "" && token !== "") {
            return {sandboxAddressesMissing: true};
        }
        return null;
    };
}

/**
 * The overview's line of the tenant's code execution as a translation key with its params: allowed with the number
 * of instances, or not allowed.
 */
export function codeExecutionLabel(tenant: Tenant): {key: string; params: Record<string, unknown>} {
    if (!tenant.codeExecution || !tenant.sandboxUrls) {
        return {key: "tenantDetail.overview.codeExecution.off", params: {}};
    }
    const count = tenant.sandboxUrls.split(",").length;
    const key = count === 1 ? "tenantDetail.overview.codeExecution.one" : "tenantDetail.overview.codeExecution.other";
    return {key, params: {count}};
}

/**
 * What the admin's check found for an instance as a chip: its label's translation key and tone (app-chip classes in
 * styles.scss).
 */
export function sandboxStatusChip(status: SandboxStatus): {label: string; tone: string} {
    switch (status) {
        case "reachable":
            return {label: "tenantDetail.overview.sandboxStatus.reachable", tone: "ok"};
        case "busy":
            return {label: "tenantDetail.overview.sandboxStatus.busy", tone: "warn"};
        case "token-refused":
            return {label: "tenantDetail.overview.sandboxStatus.tokenRefused", tone: "err"};
        case "unreachable":
            return {label: "tenantDetail.overview.sandboxStatus.unreachable", tone: "err"};
        case "unusable":
            return {label: "tenantDetail.overview.sandboxStatus.unusable", tone: "err"};
    }
}
