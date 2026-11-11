import {AbstractControl, ValidationErrors, ValidatorFn} from "@angular/forms";
import {AiModelRequest, AiProvider, hasOwnServer, ModelKind} from "../models/ai.model";

/** Like the backend: pgvector's HNSW index takes at most 2000 dimensions - the provider is asked for this many. */
export const MAX_DIMENSION = 2000;
/** Like the backend: the context length an Ollama chat model may be given. */
export const MIN_CONTEXT_LENGTH = 1024;
export const MAX_CONTEXT_LENGTH = 1_048_576;
/** Like the backend: a chat model's output limit, reasoning included - empty is the installation's (16384). */
export const MIN_OUTPUT_TOKENS = 256;
export const MAX_OUTPUT_TOKENS = 1_000_000;
/** An absolute http(s) address without user, query or fragment - whether plain http is allowed, the backend decides. */
const SERVER_ADDRESS = /^https?:\/\/[^\s/?#@]+(\/[^\s?#@]*)?$/i;
/** Like the backend: a Bedrock runtime address names its region, e.g. https://bedrock-runtime.eu-central-1.amazonaws.com. */
const BEDROCK_ADDRESS = /^https?:\/\/([^\s/?#@]*\.)?bedrock-runtime(-fips)?\.[a-z]{2}(-[a-z]+)+-\d+\.[^\s/?#@]*\/?$/;
/** An IAM access key as the catalog keeps it: its id, a colon, its secret. */
const BEDROCK_KEY = /^[A-Z0-9]{16,128}:\S+$/;

export interface AiModelFormValue {
    provider: AiProvider;
    kind: ModelKind;
    modelName: string;
    displayName: string;
    dimension: number | null;
    inputPrice: number | null;
    outputPrice: number | null;
    readsPdf: boolean;
    readsAudio: boolean;
    transcribes: boolean;
    readsScans: boolean;
    takesTemperature: boolean;
    steersReasoning: boolean;
    callsTools: boolean;
    baseUrl: string;
    /** Empty: the stored key stays (unless the host changes). */
    apiKey: string;
    removeApiKey: boolean;
    contextLength: number | null;
    maxOutputTokens: number | null;
    tierThreshold: number | null;
    tierInputPrice: number | null;
    tierOutputPrice: number | null;
    cacheReadPrice: number | null;
    cacheWritePrice: number | null;
    tierCacheReadPrice: number | null;
    tierCacheWritePrice: number | null;
    active: boolean;
}

/**
 * The provider a kind belongs to and the kind a provider belongs to: the web search is Tavily's only and the reranker
 * a TEI server's only, both ways - the dialog chooses the other half, so the server's address appears with the kind.
 */
export function providerForKind(kind: ModelKind): AiProvider | null {
    switch (kind) {
        case "WEB_SEARCH":
            return "TAVILY";
        case "RERANK":
            return "TEI";
        default:
            return null;
    }
}

export function kindForProvider(provider: AiProvider): ModelKind | null {
    switch (provider) {
        case "TAVILY":
            return "WEB_SEARCH";
        case "TEI":
            return "RERANK";
        default:
            return null;
    }
}

/** The optional prices of a tier and the cache, by their form field. */
export const TIER_AND_CACHE_PRICES = [
    "tierInputPrice",
    "tierOutputPrice",
    "cacheReadPrice",
    "cacheWritePrice",
    "tierCacheReadPrice",
    "tierCacheWritePrice",
] as const;

/**
 * The catalog's rules across fields, like the backend: Anthropic offers no embedding models, and an embedding
 * model needs its vector dimension (1..2000) - a chat model has none, its field is ignored. Image models are
 * Gemini's only for now (docs/roadmap.md point 25), the web search is Tavily's and Tavily offers nothing else. A transcribing
 * chat model must read audio; an embedding model's capabilities are ignored (sent as false). A model on an own
 * server needs the server's address; an Ollama chat model's context length is 1024..1048576 if given.
 */
export function aiModelErrors(value: AiModelFormValue): ValidationErrors | null {
    const errors: ValidationErrors = {...serverErrors(value), ...kindErrors(value), ...tierErrors(value)};
    return Object.keys(errors).length ? errors : null;
}

/**
 * Like the backend: a price tier is its threshold (a whole number above 0) with its input and output price - tier
 * prices without a threshold, or a threshold without both, would price a long request wrong without saying so. The
 * web search has no tokens, so none of it.
 */
function tierErrors(value: AiModelFormValue): ValidationErrors {
    if (value.kind === "WEB_SEARCH") {
        return {};
    }
    const tierPrices = [
        value.tierInputPrice,
        value.tierOutputPrice,
        value.tierCacheReadPrice,
        value.tierCacheWritePrice,
    ];
    if (isEmpty(value.tierThreshold)) {
        return tierPrices.some(price => !isEmpty(price)) ? {tierIncomplete: true} : {};
    }
    const threshold = Number(value.tierThreshold);
    if (!Number.isInteger(threshold) || threshold < 1) {
        return {tierThresholdRange: true};
    }
    return isEmpty(value.tierInputPrice) || isEmpty(value.tierOutputPrice) ? {tierIncomplete: true} : {};
}

/** Bedrock: the address of a region; a key entered is an IAM access key (none entered keeps the stored one). */
function bedrockErrors(address: string, apiKey: string): ValidationErrors {
    if (!BEDROCK_ADDRESS.test(address)) {
        return {bedrockAddress: true};
    }
    return apiKey === "" || BEDROCK_KEY.test(apiKey) ? {} : {bedrockKey: true};
}

function kindErrors(value: AiModelFormValue): ValidationErrors {
    if ((value.kind === "WEB_SEARCH") !== (value.provider === "TAVILY")) {
        return {webSearchTavily: true};
    }
    if ((value.kind === "RERANK") !== (value.provider === "TEI")) {
        return {rerankTei: true};
    }
    if (value.kind === "WEB_SEARCH" || value.kind === "RERANK") {
        return {};
    }
    if (value.provider === "BEDROCK" && value.kind !== "CHAT") {
        return {bedrockOnlyChat: true};
    }
    if (value.kind === "IMAGE") {
        return value.provider === "GEMINI" ? {} : {imageNotGemini: true};
    }
    if (value.kind === "CHAT") {
        return {
            ...(value.transcribes && !value.readsAudio ? {transcribesWithoutAudio: true} : {}),
            ...outputTokenErrors(value.maxOutputTokens),
        };
    }
    if (value.provider === "ANTHROPIC") {
        return {anthropicEmbedding: true};
    }
    const dimension = value.dimension;
    if (dimension === null || `${dimension}` === "") {
        return {dimensionRequired: true};
    }
    if (!Number.isInteger(Number(dimension)) || dimension < 1 || dimension > MAX_DIMENSION) {
        return {dimensionRange: true};
    }
    return {};
}

function outputTokenErrors(maxOutputTokens: number | null): ValidationErrors {
    if (isEmpty(maxOutputTokens)) {
        return {};
    }
    const tokens = Number(maxOutputTokens);
    return Number.isInteger(tokens) && tokens >= MIN_OUTPUT_TOKENS && tokens <= MAX_OUTPUT_TOKENS
        ? {}
        : {maxOutputTokensRange: true};
}

function isEmpty(value: number | null): boolean {
    return value === null || `${value}` === "";
}

function serverErrors(value: AiModelFormValue): ValidationErrors {
    if (!hasOwnServer(value.provider)) {
        return {};
    }
    const address = value.baseUrl.trim();
    if (address === "") {
        return {baseUrlRequired: true};
    }
    if (!SERVER_ADDRESS.test(address)) {
        return {baseUrlInvalid: true};
    }
    if (value.provider === "BEDROCK") {
        return bedrockErrors(address, value.apiKey.trim());
    }
    const contextLength = takesContextLength(value) ? value.contextLength : null;
    if (contextLength === null || `${contextLength}` === "") {
        return {};
    }
    const length = Number(contextLength);
    return Number.isInteger(length) && length >= MIN_CONTEXT_LENGTH && length <= MAX_CONTEXT_LENGTH
        ? {}
        : {contextLengthRange: true};
}

/** Only an Ollama chat model is told its context length - an OpenAI-compatible server sets its own. */
export function takesContextLength(value: Pick<AiModelFormValue, "provider" | "kind">): boolean {
    return value.provider === "OLLAMA" && value.kind === "CHAT";
}

/** aiModelErrors as a validator of the dialog's form group. */
export function aiModelRules(): ValidatorFn {
    return (group: AbstractControl): ValidationErrors | null => aiModelErrors(group.value as AiModelFormValue);
}

/**
 * The request of the model dialog: trimmed names, a dimension only for an embedding model, capabilities only for a chat
 * model, address and key only for a model on an own server - an empty key field keeps the stored key. The web search has
 * one price, per 1,000 searches: its output price is 0, and it has no tier or cache prices; an empty one is left out.
 */
export function buildAiModelRequest(value: AiModelFormValue): AiModelRequest {
    const chat = value.kind === "CHAT";
    const request: AiModelRequest = {
        provider: value.provider,
        kind: value.kind,
        modelName: value.modelName.trim(),
        displayName: value.displayName.trim(),
        inputPrice: Number(value.inputPrice ?? 0),
        outputPrice: value.kind === "WEB_SEARCH" ? 0 : Number(value.outputPrice ?? 0),
        readsPdf: chat && value.readsPdf,
        readsAudio: chat && value.readsAudio,
        transcribes: chat && value.readsAudio && value.transcribes,
        readsScans: chat && value.readsScans,
        active: value.active,
    };
    if (value.kind === "EMBEDDING" && value.dimension !== null) {
        request.dimension = Number(value.dimension);
    }
    if (value.kind !== "WEB_SEARCH") {
        if (!isEmpty(value.tierThreshold)) {
            request.tierThreshold = Number(value.tierThreshold);
        }
        for (const field of TIER_AND_CACHE_PRICES) {
            if (!isEmpty(value[field])) {
                request[field] = Number(value[field]);
            }
        }
    }
    if (chat) {
        request.takesTemperature = value.takesTemperature;
        request.steersReasoning = value.steersReasoning;
        request.callsTools = value.callsTools;
        if (!isEmpty(value.maxOutputTokens)) {
            request.maxOutputTokens = Number(value.maxOutputTokens);
        }
    }
    if (!hasOwnServer(value.provider)) {
        return request;
    }
    request.baseUrl = value.baseUrl.trim();
    const apiKey = value.apiKey.trim();
    if (apiKey !== "") {
        request.apiKey = apiKey;
    } else if (value.removeApiKey) {
        request.removeApiKey = true;
    }
    if (takesContextLength(value) && value.contextLength !== null && `${value.contextLength}` !== "") {
        request.contextLength = Number(value.contextLength);
    }
    return request;
}
