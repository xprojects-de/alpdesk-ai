import {aiModelErrors, AiModelFormValue, buildAiModelRequest, kindForProvider, providerForKind} from "./ai-model.util";

describe("ai-model.util", () => {
    const value = (changes: Partial<AiModelFormValue>): AiModelFormValue => ({
        provider: "OPENAI",
        kind: "CHAT",
        modelName: " gpt-5 ",
        displayName: " GPT-5 ",
        dimension: null,
        inputPrice: 1.25,
        outputPrice: 10,
        readsPdf: false,
        readsAudio: false,
        transcribes: false,
        readsScans: false,
        takesTemperature: true,
        steersReasoning: false,
        callsTools: true,
        baseUrl: "",
        apiKey: "",
        removeApiKey: false,
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
        ...changes,
    });

    it("takes a price tier only whole - its threshold with both its prices", () => {
        const tier = {tierThreshold: 100000, tierInputPrice: 0.5, tierOutputPrice: 2.5};
        expect(aiModelErrors(value(tier))).toBeNull();
        expect(aiModelErrors(value({tierThreshold: 100000, tierInputPrice: 0.5}))).toEqual({tierIncomplete: true});
        expect(aiModelErrors(value({tierInputPrice: 0.5, tierOutputPrice: 2.5}))).toEqual({tierIncomplete: true});
        expect(aiModelErrors(value({tierCacheReadPrice: 0.05}))).toEqual({tierIncomplete: true});
        expect(aiModelErrors(value({...tier, tierThreshold: 0}))).toEqual({tierThresholdRange: true});
        expect(aiModelErrors(value({...tier, tierThreshold: 1.5}))).toEqual({tierThresholdRange: true});
    });

    it("takes cache prices on their own and sends only the prices filled in", () => {
        expect(aiModelErrors(value({cacheReadPrice: 0.01, cacheWritePrice: 0.125}))).toBeNull();
        const request = buildAiModelRequest(
            value({
                tierThreshold: 100000,
                tierInputPrice: 0.5,
                tierOutputPrice: 2.5,
                cacheReadPrice: 0.01,
                tierCacheReadPrice: 0.05,
            }),
        );
        expect(request).toEqual(
            expect.objectContaining({
                tierThreshold: 100000,
                tierInputPrice: 0.5,
                tierOutputPrice: 2.5,
                cacheReadPrice: 0.01,
                tierCacheReadPrice: 0.05,
            }),
        );
        expect(request.cacheWritePrice).toBeUndefined();
        expect(request.tierCacheWritePrice).toBeUndefined();
    });

    it("gives the web search no tier or cache prices", () => {
        const request = buildAiModelRequest(
            value({provider: "TAVILY", kind: "WEB_SEARCH", tierThreshold: 5, tierInputPrice: 1, cacheReadPrice: 1}),
        );
        expect(request.tierThreshold).toBeUndefined();
        expect(request.cacheReadPrice).toBeUndefined();
        expect(aiModelErrors(value({provider: "TAVILY", kind: "WEB_SEARCH", tierInputPrice: 1}))).toBeNull();
    });

    it("accepts a chat model without dimension, whatever is left in the field", () => {
        expect(aiModelErrors(value({}))).toBeNull();
        expect(aiModelErrors(value({dimension: 99999}))).toBeNull();
    });

    it("refuses an embedding model of Anthropic", () => {
        expect(aiModelErrors(value({provider: "ANTHROPIC", kind: "EMBEDDING", dimension: 1024}))).toEqual({
            anthropicEmbedding: true,
        });
    });

    it("keeps the web search to Tavily and Tavily to the web search", () => {
        expect(aiModelErrors(value({provider: "TAVILY", kind: "WEB_SEARCH"}))).toBeNull();
        expect(aiModelErrors(value({provider: "OPENAI", kind: "WEB_SEARCH"}))).toEqual({webSearchTavily: true});
        expect(aiModelErrors(value({provider: "TAVILY", kind: "CHAT"}))).toEqual({webSearchTavily: true});
        expect(aiModelErrors(value({provider: "TAVILY", kind: "EMBEDDING"}))).toEqual({webSearchTavily: true});
    });

    it("keeps the reranker to a TEI server and a TEI server to the reranker", () => {
        const tei = {baseUrl: "http://alpdesk-ai-reranker"};
        expect(aiModelErrors(value({provider: "TEI", kind: "RERANK", ...tei}))).toBeNull();
        expect(aiModelErrors(value({provider: "OLLAMA", kind: "RERANK", ...tei}))).toEqual({rerankTei: true});
        expect(aiModelErrors(value({provider: "TEI", kind: "EMBEDDING", ...tei}))).toEqual({rerankTei: true});
        expect(aiModelErrors(value({provider: "TEI", kind: "RERANK"}))).toEqual({baseUrlRequired: true});
    });

    it("pairs the web search with Tavily and the reranker with a TEI server, both ways", () => {
        expect(providerForKind("RERANK")).toBe("TEI");
        expect(providerForKind("WEB_SEARCH")).toBe("TAVILY");
        expect(providerForKind("CHAT")).toBeNull();
        expect(kindForProvider("TEI")).toBe("RERANK");
        expect(kindForProvider("TAVILY")).toBe("WEB_SEARCH");
        expect(kindForProvider("OLLAMA")).toBeNull();
    });

    it("sends a reranker with its server and without dimension or chat capabilities", () => {
        const request = buildAiModelRequest(
            value({
                provider: "TEI",
                kind: "RERANK",
                baseUrl: "http://alpdesk-ai-reranker",
                dimension: 1024,
                readsPdf: true,
            }),
        );
        expect(request).toMatchObject({kind: "RERANK", baseUrl: "http://alpdesk-ai-reranker", readsPdf: false});
        expect(request.dimension).toBeUndefined();
        expect(request.callsTools).toBeUndefined();
    });

    it("sends the web search with its one price and nothing of a chat model", () => {
        const request = buildAiModelRequest(
            value({provider: "TAVILY", kind: "WEB_SEARCH", inputPrice: 8, outputPrice: null, readsPdf: true}),
        );
        expect(request).toMatchObject({kind: "WEB_SEARCH", inputPrice: 8, outputPrice: 0, readsPdf: false});
        expect(request.callsTools).toBeUndefined();
    });

    it("accepts image models of Gemini only", () => {
        expect(aiModelErrors(value({provider: "GEMINI", kind: "IMAGE"}))).toBeNull();
        expect(aiModelErrors(value({provider: "OPENAI", kind: "IMAGE"}))).toEqual({imageNotGemini: true});
        expect(aiModelErrors(value({provider: "ANTHROPIC", kind: "IMAGE"}))).toEqual({imageNotGemini: true});
    });

    it("sends an image model without dimension, capabilities or output limit", () => {
        const request = buildAiModelRequest(
            value({
                provider: "GEMINI",
                kind: "IMAGE",
                dimension: 1536,
                readsPdf: true,
                readsScans: true,
                maxOutputTokens: 4096,
            }),
        );
        expect(request).toMatchObject({kind: "IMAGE", readsPdf: false, readsScans: false});
        expect(request.dimension).toBeUndefined();
        expect(request.callsTools).toBeUndefined();
        expect(request.maxOutputTokens).toBeUndefined();
    });

    it("requires a dimension of 1..2000 for an embedding model", () => {
        const embedding = (dimension: number | null) => aiModelErrors(value({kind: "EMBEDDING", dimension}));
        expect(embedding(1536)).toBeNull();
        expect(embedding(2000)).toBeNull();
        expect(embedding(null)).toEqual({dimensionRequired: true});
        expect(embedding(0)).toEqual({dimensionRange: true});
        expect(embedding(2001)).toEqual({dimensionRange: true});
        expect(embedding(12.5)).toEqual({dimensionRange: true});
    });

    it("sends a dimension only for an embedding model, names trimmed", () => {
        expect(buildAiModelRequest(value({dimension: 1536}))).toEqual({
            provider: "OPENAI",
            kind: "CHAT",
            modelName: "gpt-5",
            displayName: "GPT-5",
            inputPrice: 1.25,
            outputPrice: 10,
            readsPdf: false,
            readsAudio: false,
            transcribes: false,
            readsScans: false,
            takesTemperature: true,
            steersReasoning: false,
            callsTools: true,
            active: true,
        });
        expect(buildAiModelRequest(value({kind: "EMBEDDING", dimension: 1536})).dimension).toBe(1536);
        expect(buildAiModelRequest(value({kind: "EMBEDDING", dimension: 1536})).callsTools).toBeUndefined();
    });

    it("lets only a chat model that reads audio transcribe", () => {
        expect(aiModelErrors(value({readsAudio: true, transcribes: true}))).toBeNull();
        expect(aiModelErrors(value({transcribes: true}))).toEqual({transcribesWithoutAudio: true});
    });

    it("sends the capabilities only for a chat model", () => {
        const capabilities = {readsPdf: true, readsAudio: true, transcribes: true};
        expect(buildAiModelRequest(value(capabilities))).toMatchObject(capabilities);
        expect(buildAiModelRequest(value({...capabilities, kind: "EMBEDDING", dimension: 1536}))).toMatchObject({
            readsPdf: false,
            readsAudio: false,
            transcribes: false,
        });
    });

    describe("a model on an own server", () => {
        const server = (changes: Partial<AiModelFormValue>) =>
            value({provider: "OPENAI_COMPATIBLE", baseUrl: "https://models.example.com/v1", ...changes});

        it("needs an absolute http(s) address without user, query or fragment", () => {
            expect(aiModelErrors(server({}))).toBeNull();
            expect(aiModelErrors(server({baseUrl: "http://ollama-intern:11434"}))).toBeNull();
            expect(aiModelErrors(server({baseUrl: " "}))).toEqual({baseUrlRequired: true});
            expect(aiModelErrors(server({baseUrl: "models.example.com/v1"}))).toEqual({baseUrlInvalid: true});
            expect(aiModelErrors(server({baseUrl: "ftp://models.example.com"}))).toEqual({baseUrlInvalid: true});
            expect(aiModelErrors(server({baseUrl: "https://user:pw@models.example.com"}))).toEqual({
                baseUrlInvalid: true,
            });
            expect(aiModelErrors(server({baseUrl: "https://models.example.com/v1?key=x"}))).toEqual({
                baseUrlInvalid: true,
            });
        });

        it("takes Bedrock at a region's address with an IAM access key - its chat models only", () => {
            const bedrock = (changes: Partial<AiModelFormValue>) =>
                aiModelErrors(
                    server({
                        provider: "BEDROCK",
                        baseUrl: "https://bedrock-runtime.eu-central-1.amazonaws.com",
                        apiKey: "AKIAEXAMPLE123456789:geheimes/Secret+1234",
                        ...changes,
                    }),
                );
            expect(bedrock({})).toBeNull();
            expect(bedrock({apiKey: ""})).toBeNull();
            expect(bedrock({baseUrl: "https://bedrock-mantle.eu-central-1.api.aws/anthropic/v1"})).toEqual({
                bedrockAddress: true,
            });
            expect(bedrock({baseUrl: "https://models.example.com"})).toEqual({bedrockAddress: true});
            expect(bedrock({apiKey: "AKIAEXAMPLE123456789"})).toEqual({bedrockKey: true});
            expect(bedrock({kind: "EMBEDDING", dimension: 1024})).toEqual({bedrockOnlyChat: true});
        });

        it("checks an Ollama chat model's context length only", () => {
            const ollama = (contextLength: number | null, kind: "CHAT" | "EMBEDDING" = "CHAT") =>
                aiModelErrors(server({provider: "OLLAMA", kind, contextLength, dimension: 1024}));
            expect(ollama(null)).toBeNull();
            expect(ollama(32768)).toBeNull();
            expect(ollama(512)).toEqual({contextLengthRange: true});
            expect(ollama(2_000_000)).toEqual({contextLengthRange: true});
            expect(ollama(512, "EMBEDDING")).toBeNull();
            expect(aiModelErrors(server({contextLength: 512}))).toBeNull();
        });

        it("sends address, a new key or its removal, and the context length where it belongs", () => {
            expect(buildAiModelRequest(server({baseUrl: " https://models.example.com/v1 ", apiKey: " sk-1 "}))).toEqual(
                expect.objectContaining({baseUrl: "https://models.example.com/v1", apiKey: "sk-1"}),
            );
            const kept = buildAiModelRequest(server({}));
            expect(kept.apiKey).toBeUndefined();
            expect(kept.removeApiKey).toBeUndefined();
            expect(buildAiModelRequest(server({removeApiKey: true})).removeApiKey).toBe(true);
            expect(buildAiModelRequest(server({provider: "OLLAMA", contextLength: 65536})).contextLength).toBe(65536);
            expect(buildAiModelRequest(server({contextLength: 65536})).contextLength).toBeUndefined();
            expect(buildAiModelRequest(value({baseUrl: "https://x.example.com", apiKey: "k"})).baseUrl).toBeUndefined();
            expect(buildAiModelRequest(server({callsTools: false})).callsTools).toBe(false);
        });
    });

    describe("output limit", () => {
        it("a chat model may have one of 256..1000000 tokens; empty is the installation's", () => {
            expect(aiModelErrors(value({maxOutputTokens: null}))).toBeNull();
            expect(aiModelErrors(value({maxOutputTokens: 32768}))).toBeNull();
            expect(aiModelErrors(value({maxOutputTokens: 100}))).toEqual({maxOutputTokensRange: true});
            expect(aiModelErrors(value({maxOutputTokens: 2_000_000}))).toEqual({maxOutputTokensRange: true});
            expect(aiModelErrors(value({maxOutputTokens: 1.5}))).toEqual({maxOutputTokensRange: true});
        });

        it("is sent for a chat model only", () => {
            expect(buildAiModelRequest(value({maxOutputTokens: 32768})).maxOutputTokens).toBe(32768);
            expect(buildAiModelRequest(value({maxOutputTokens: null})).maxOutputTokens).toBeUndefined();
            expect(
                buildAiModelRequest(value({kind: "EMBEDDING", dimension: 1024, maxOutputTokens: 32768}))
                    .maxOutputTokens,
            ).toBeUndefined();
            expect(aiModelErrors(value({kind: "EMBEDDING", dimension: 1024, maxOutputTokens: 5}))).toBeNull();
        });
    });
});
