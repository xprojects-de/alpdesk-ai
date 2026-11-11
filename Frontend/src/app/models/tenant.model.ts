/**
 * The languages the full-text search can read documents in - PostgreSQL's built-in text search configurations, as the
 * backend's SearchLanguage. SIMPLE is no language: words are found as written.
 */
export const SEARCH_LANGUAGES = [
    "SIMPLE",
    "ARABIC",
    "ARMENIAN",
    "BASQUE",
    "CATALAN",
    "DANISH",
    "DUTCH",
    "ENGLISH",
    "ESTONIAN",
    "FINNISH",
    "FRENCH",
    "GERMAN",
    "GREEK",
    "HINDI",
    "HUNGARIAN",
    "INDONESIAN",
    "IRISH",
    "ITALIAN",
    "LITHUANIAN",
    "NEPALI",
    "NORWEGIAN",
    "PORTUGUESE",
    "ROMANIAN",
    "RUSSIAN",
    "SERBIAN",
    "SPANISH",
    "SWEDISH",
    "TAMIL",
    "TURKISH",
    "YIDDISH",
] as const;

export type SearchLanguage = (typeof SEARCH_LANGUAGES)[number];

export interface Tenant {
    id: number;
    name: string;
    active: boolean;
    contactEmail: string | null;
    notes: string | null;
    /** The tenant's own database, named by the backend (e.g. tenant_ab12cd). */
    databaseName: string;
    /** The tenant may use the admin's global provider keys; that usage is billed. */
    globalKeysAllowed: boolean;
    /** The admin lets the tenant's admins keep scheduled tasks (docs/architecture/tasks.md). */
    tasksAllowed: boolean;
    /** The admin lets the tenant connect A2A agents (docs/architecture/a2a.md). */
    a2aAllowed: boolean;
    /** The tenant's chat may run Python and shell commands (run_python, run_shell): its own sandbox is entered. */
    codeExecution: boolean;
    /** The tenant's own sandbox instances, comma separated; null: none. */
    sandboxUrls: string | null;
    /** The last characters of the sandbox token - the token itself is never sent out. */
    sandboxTokenHint: string | null;
    /** Chosen by the tenant's users on the documents page. */
    embeddingModelId: number | null;
    /** The reranker of its knowledge search; null: off. */
    rerankingModelId: number | null;
    /** The language the full-text search reads its documents in. */
    searchLanguage: SearchLanguage;
    createdAt: string;
    updatedAt: string;
}

export interface TenantRequest {
    name: string;
    active: boolean;
    contactEmail: string | null;
    notes: string | null;
    globalKeysAllowed: boolean;
    tasksAllowed: boolean;
    a2aAllowed: boolean;
    /** Comma separated; null: unchanged (none on create), "": remove the sandbox with its token. */
    sandboxUrls: string | null;
    /** Write only; null: unchanged. */
    sandboxToken: string | null;
    /** On create only; null: none (SIMPLE). Changed through its own call - it builds the search again. */
    searchLanguage: SearchLanguage | null;
}

/**
 * What the admin's check found for one instance of a tenant's sandbox - "unusable": it answers but takes no session
 * until it is started again (its log says why).
 */
export type SandboxStatus = "reachable" | "busy" | "token-refused" | "unreachable" | "unusable";

export interface SandboxInstance {
    url: string;
    status: SandboxStatus;
    /** The video image (it examines videos); null when it did not answer. */
    video: boolean | null;
    /** An image built on ours with a README of its own programs; null when it did not answer. */
    extras: boolean | null;
}

/**
 * The admin's check of a tenant's sandbox: every instance, and whether those answering carry the same README of their
 * own programs - the model gets it only then.
 */
export interface SandboxCheck {
    instances: SandboxInstance[];
    extrasAgree: boolean;
}

export interface ApiKey {
    id: number;
    name: string;
    keyPrefix: string;
    /** false: every question is answered on its own and nothing is stored - files may come with it. */
    keepsHistory: boolean;
    /** Its chat questions may search the web. */
    webSearch: boolean;
    createdAt: string;
    lastUsedAt: string | null;
    /** The knowledge areas the key finds and pushes into besides the company knowledge. */
    areaIds: number[];
    /** The scheduled tasks the client software may start with the key (/api/v1/tasks). */
    taskIds: number[];
    /** The connected software its chat questions may use (roadmap point 42). */
    connectionIds: number[];
}

/** What a new key gets, or what a change sets - left out: none on create, unchanged on a change. */
export interface ApiKeyRequest {
    name?: string;
    keepsHistory?: boolean;
    webSearch?: boolean;
    areaIds?: number[];
    taskIds?: number[];
    connectionIds?: number[];
}

/** Only ever returned by the create call - the full key is not stored anywhere. */
export interface ApiKeyCreated {
    key: ApiKey;
    plainKey: string;
}

/** The ids of the catalog models a tenant may use, active or not - assigned by the admin. */
export interface TenantModels {
    modelIds: number[];
}

/**
 * Whose tenant settings a page or dialog keeps: "self" - the logged-in tenant admin's own tenant (/tenant/admin); a
 * tenant's id - that tenant, kept by the admin (/admin/tenants/{id}). Both APIs have the same sub-paths.
 */
export type TenantScope = "self" | number;
