import {AiModel, ModelKind} from "../models/ai.model";

/**
 * Whether a tenant has the chat's web search, as the switches of groups, API keys and the MCP server ask: the admin
 * assigned it an active catalog entry of kind WEB_SEARCH. Without one the switches are not shown - like the image
 * model's setting (docs/architecture/ai-features.md "Web search"). A missing Tavily key shows on the provider keys.
 */

/** A tenant admin's view: the tenant's own active models (GET /tenant/ai/models) hold the entry. */
export function webSearchAmong(models: {kind: ModelKind}[]): boolean {
    return models.some(model => model.kind === "WEB_SEARCH");
}

/** The admin's view: an active entry of the catalog is among the tenant's assigned models (null: not loaded yet). */
export function webSearchAssigned(catalog: AiModel[], assignedIds: number[] | null): boolean {
    if (assignedIds === null) {
        return false;
    }
    return catalog.some(model => model.kind === "WEB_SEARCH" && model.active && assignedIds.includes(model.id));
}
