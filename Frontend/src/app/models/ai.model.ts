import {SearchLanguage} from "./tenant.model";

/** TEI: a server speaking the /rerank API of Hugging Face Text Embeddings Inference - the reranker's only provider. */
/** BEDROCK: Amazon Bedrock's chat models - Claude in an EU region with the installation's AWS account. */
export type AiProvider =
    "ANTHROPIC" | "OPENAI" | "GEMINI" | "OLLAMA" | "OPENAI_COMPATIBLE" | "BEDROCK" | "TAVILY" | "TEI";
/**
 * WEB_SEARCH: the chat's web search (Tavily) - no model, priced per 1,000 searches. RERANK: reorders the knowledge
 * search's candidates (TEI), priced per million tokens.
 */
export type ModelKind = "CHAT" | "EMBEDDING" | "IMAGE" | "WEB_SEARCH" | "RERANK";
/** Whose provider key a call uses: the tenant's own or the admin's global one (billed). */
export type KeySource = "OWN" | "GLOBAL";

/** The providers of the model catalog. */
export const PROVIDERS: AiProvider[] = [
    "ANTHROPIC",
    "OPENAI",
    "GEMINI",
    "OLLAMA",
    "OPENAI_COMPATIBLE",
    "BEDROCK",
    "TAVILY",
    "TEI",
];

/** The providers with keys under API-Keys - a model on an own server has its address and key in the catalog. */
export const KEY_PROVIDERS: AiProvider[] = ["ANTHROPIC", "OPENAI", "GEMINI", "TAVILY"];

/** Translation keys of the providers' labels - a template translates them. */
export const PROVIDER_LABELS: Record<AiProvider, string> = {
    ANTHROPIC: "models.provider.ANTHROPIC",
    OPENAI: "models.provider.OPENAI",
    GEMINI: "models.provider.GEMINI",
    OLLAMA: "models.provider.OLLAMA",
    OPENAI_COMPATIBLE: "models.provider.OPENAI_COMPATIBLE",
    BEDROCK: "models.provider.BEDROCK",
    TAVILY: "models.provider.TAVILY",
    TEI: "models.provider.TEI",
};

/** Like the backend: its models run on a server entered with the model - Bedrock's at its region's address. */
export function hasOwnServer(provider: AiProvider): boolean {
    return provider === "OLLAMA" || provider === "OPENAI_COMPATIBLE" || provider === "BEDROCK" || provider === "TEI";
}

/** Translation keys of the kinds' labels. */
export const KIND_LABELS: Record<ModelKind, string> = {
    CHAT: "models.kind.CHAT",
    EMBEDDING: "models.kind.EMBEDDING",
    IMAGE: "models.kind.IMAGE",
    WEB_SEARCH: "models.kind.WEB_SEARCH",
    RERANK: "models.kind.RERANK",
};

/** Translation keys of the key sources' labels. */
export const KEY_SOURCE_LABELS: Record<KeySource, string> = {
    OWN: "models.keySource.OWN",
    GLOBAL: "models.keySource.GLOBAL",
};

/** A model of the admin's catalog; prices in EUR per million tokens. */
export interface AiModel {
    id: number;
    provider: AiProvider;
    kind: ModelKind;
    modelName: string;
    displayName: string;
    dimension: number | null;
    inputPrice: number;
    outputPrice: number;
    /** What a chat model reads itself (images every chat model reads); always false for an embedding model. */
    readsPdf: boolean;
    readsAudio: boolean;
    /** Writes the transcripts of uploaded audio files - at most one model does. */
    transcribes: boolean;
    /** Reads the images and scanned pages of the company's documents - at most one model does. */
    readsScans: boolean;
    /** A chat model takes a temperature (the tenant's creativity) - reasoning models refuse one. */
    takesTemperature: boolean;
    /** A chat model's reasoning effort is set (the tenant's "Nachdenken"). */
    steersReasoning: boolean;
    /** A chat model calls tools; without, the chat searches and reads the files for it beforehand. */
    callsTools: boolean;
    /** Models on own servers: the server's address. */
    baseUrl: string | null;
    /** The server key's last characters; null without a key. The key itself never comes back. */
    keyHint: string | null;
    /** OLLAMA chat models: the context length; null = the default (32768). */
    contextLength: number | null;
    /** Chat models: the most tokens one answer may take, reasoning included; null = the installation's default. */
    maxOutputTokens: number | null;
    /** A request whose prompt (cached tokens included) is longer pays the tier prices; null = one price. */
    tierThreshold: number | null;
    tierInputPrice: number | null;
    tierOutputPrice: number | null;
    /** A token read from / written to the provider's cache; null = like an input token. */
    cacheReadPrice: number | null;
    cacheWritePrice: number | null;
    tierCacheReadPrice: number | null;
    tierCacheWritePrice: number | null;
    active: boolean;
    createdAt: string;
    updatedAt: string;
}

export interface AiModelRequest {
    provider: AiProvider;
    kind: ModelKind;
    modelName: string;
    displayName: string;
    /** Only for an embedding model. */
    dimension?: number;
    inputPrice: number;
    outputPrice: number;
    readsPdf?: boolean;
    readsAudio?: boolean;
    transcribes?: boolean;
    readsScans?: boolean;
    takesTemperature?: boolean;
    steersReasoning?: boolean;
    callsTools?: boolean;
    baseUrl?: string;
    /** Left out: unchanged - another host than before drops the stored key. */
    apiKey?: string;
    removeApiKey?: boolean;
    contextLength?: number;
    /** Chat models only; left out = the installation's default. */
    maxOutputTokens?: number;
    /** Left out: one price; with it both tier prices. */
    tierThreshold?: number;
    tierInputPrice?: number;
    tierOutputPrice?: number;
    /** Left out: like an input token (of the tier). */
    cacheReadPrice?: number;
    cacheWritePrice?: number;
    tierCacheReadPrice?: number;
    tierCacheWritePrice?: number;
    active?: boolean;
}

/** A stored provider key; the key itself never comes back, only its hint (e.g. "sk-…a1b2"). */
export interface ProviderKey {
    provider: AiProvider;
    keyHint: string;
    updatedAt: string;
}

/** One line of the usage of a month: a tenant's calls of one model with one key source. */
export interface UsageRow {
    tenantId: number;
    tenantName: string;
    provider: AiProvider;
    modelName: string;
    kind: ModelKind;
    keySource: KeySource;
    calls: number;
    inputTokens: number;
    outputTokens: number;
    /** Read from / written to the provider's cache - apart from inputTokens. */
    cacheReadTokens: number;
    cacheWriteTokens: number;
    /** Some token counts were estimated (the provider reported none). */
    estimated: boolean;
    /** EUR. */
    amount: number;
}

/** An active model as a tenant user sees it; keySource null = no key for its provider, not usable. */
export interface TenantModel {
    id: number;
    provider: AiProvider;
    kind: ModelKind;
    displayName: string;
    dimension: number | null;
    keySource: KeySource | null;
}

export interface AiSettings {
    embeddingModelId: number | null;
    /** The reranker of the knowledge search; null: off. */
    rerankingModelId: number | null;
    searchLanguage: SearchLanguage;
    globalKeysAllowed: boolean;
}
