import {AiModel, KIND_LABELS, ModelKind} from "../models/ai.model";

export interface TenantModelGroup {
    kind: ModelKind;
    /** A translation key. */
    label: string;
    models: AiModel[];
}

const KIND_ORDER: ModelKind[] = ["CHAT", "EMBEDDING", "IMAGE", "WEB_SEARCH", "RERANK"];

/**
 * The catalog as the model dialog lists it: chat, embedding, image models, the web search, then the rerankers, each in
 * the catalog's order.
 */
export function tenantModelGroups(models: AiModel[]): TenantModelGroup[] {
    const groups: TenantModelGroup[] = [];
    for (const kind of KIND_ORDER) {
        const ofKind = models.filter(model => model.kind === kind);
        if (ofKind.length > 0) {
            groups.push({kind, label: KIND_LABELS[kind], models: ofKind});
        }
    }
    return groups;
}

/**
 * The embedding models the admin can choose for a tenant's documents: active ones assigned to the tenant - and the one
 * chosen now even if it was deactivated or unassigned meanwhile, so the select still shows it.
 */
export function embeddingModelChoices(models: AiModel[], assignedIds: number[], chosenId: number | null): AiModel[] {
    return models.filter(
        model =>
            model.kind === "EMBEDDING" && (model.id === chosenId || (model.active && assignedIds.includes(model.id))),
    );
}

/**
 * The rerankers the admin can choose for a tenant's search: active ones assigned to the tenant - and the one chosen now
 * even if it was deactivated meanwhile, so the select still shows it.
 */
export function rerankerChoices(models: AiModel[], assignedIds: number[], chosenId: number | null): AiModel[] {
    return models.filter(
        model => model.kind === "RERANK" && (model.id === chosenId || (model.active && assignedIds.includes(model.id))),
    );
}

/**
 * The ids the model dialog sends: the checked models that are still in the catalog, in its order. The model the
 * tenant's documents are embedded with always stays - the backend refuses leaving it out (409).
 */
export function buildTenantModelIds(
    models: AiModel[],
    checked: ReadonlySet<number>,
    embeddingModelId: number | null,
): number[] {
    return models.filter(model => checked.has(model.id) || model.id === embeddingModelId).map(model => model.id);
}
