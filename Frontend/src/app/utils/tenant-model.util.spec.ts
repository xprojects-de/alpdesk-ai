import {AiModel, ModelKind} from "../models/ai.model";
import {buildTenantModelIds, embeddingModelChoices, rerankerChoices, tenantModelGroups} from "./tenant-model.util";

describe("tenant model util", () => {
    const model = (id: number, kind: ModelKind): AiModel => ({
        id,
        provider: "GEMINI",
        kind,
        modelName: `model-${id}`,
        displayName: `Modell ${id}`,
        dimension: kind === "EMBEDDING" ? 768 : null,
        inputPrice: 0,
        outputPrice: 0,
        readsPdf: false,
        readsAudio: false,
        transcribes: false,
        readsScans: false,
        takesTemperature: true,
        steersReasoning: false,
        callsTools: true,
        baseUrl: null,
        keyHint: null,
        contextLength: null,
        maxOutputTokens: null,
        tierThreshold: null,
        tierInputPrice: null,
        tierOutputPrice: null,
        cacheReadPrice: null,
        cacheWritePrice: null,
        tierCacheReadPrice: null,
        tierCacheWritePrice: null,
        active: true,
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
    });

    const catalog = [model(1, "EMBEDDING"), model(2, "CHAT"), model(3, "CHAT")];

    describe("tenantModelGroups", () => {
        it("lists the chat models first, then the embedding and the image models, in the catalog's order", () => {
            expect(
                tenantModelGroups([...catalog, model(4, "IMAGE")]).map(group => [
                    group.label,
                    group.models.map(m => m.id),
                ]),
            ).toEqual([
                ["models.kind.CHAT", [2, 3]],
                ["models.kind.EMBEDDING", [1]],
                ["models.kind.IMAGE", [4]],
            ]);
        });

        it("lists the rerankers last", () => {
            expect(tenantModelGroups([model(6, "RERANK"), ...catalog]).map(group => group.kind)).toEqual([
                "CHAT",
                "EMBEDDING",
                "RERANK",
            ]);
        });

        it("leaves out a kind without models", () => {
            expect(tenantModelGroups([model(2, "CHAT")]).map(group => group.kind)).toEqual(["CHAT"]);
            expect(tenantModelGroups([])).toEqual([]);
        });
    });

    describe("rerankerChoices", () => {
        const inactive = {...model(7, "RERANK"), active: false};
        const all = [...catalog, model(6, "RERANK"), model(8, "RERANK"), inactive];

        it("offers the active rerankers assigned to the tenant", () => {
            expect(rerankerChoices(all, [1, 6, 7], null).map(m => m.id)).toEqual([6]);
        });

        it("keeps the chosen reranker even when it was deactivated", () => {
            expect(rerankerChoices(all, [6, 7], 7).map(m => m.id)).toEqual([6, 7]);
        });
    });

    describe("embeddingModelChoices", () => {
        const inactive = {...model(4, "EMBEDDING"), active: false};
        const all = [...catalog, model(5, "EMBEDDING"), inactive];

        it("offers the active embedding models assigned to the tenant", () => {
            expect(embeddingModelChoices(all, [1, 2, 4], null).map(m => m.id)).toEqual([1]);
        });

        it("keeps the chosen model even when it is no longer assigned or active", () => {
            expect(embeddingModelChoices(all, [1], 4).map(m => m.id)).toEqual([1, 4]);
            expect(embeddingModelChoices(all, [], 5).map(m => m.id)).toEqual([5]);
        });
    });

    describe("buildTenantModelIds", () => {
        it("sends the checked models in the catalog's order", () => {
            expect(buildTenantModelIds(catalog, new Set([3, 1]), null)).toEqual([1, 3]);
        });

        it("sends nothing when nothing is checked", () => {
            expect(buildTenantModelIds(catalog, new Set(), null)).toEqual([]);
        });

        it("keeps the model the tenant's documents are embedded with", () => {
            expect(buildTenantModelIds(catalog, new Set([2]), 1)).toEqual([1, 2]);
        });

        it("drops ids no longer in the catalog", () => {
            expect(buildTenantModelIds(catalog, new Set([2, 99]), null)).toEqual([2]);
        });
    });
});
