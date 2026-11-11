package de.alpdesk.services.ai;

import de.alpdesk.dto.ai.AiModelRequest;
import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.repositories.ai.AiModelRepository;
import de.alpdesk.security.SecretCipher;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalog of AI models and their prices, maintained by the admin - for the models on own servers with the server
 * they run on (docs/architecture/ai-features.md "Own model servers").
 */
@Singleton
public class AiModelService {

    /**
     * pgvector's HNSW index takes vectors of at most 2000 dimensions (services.document.TenantEmbeddings). The
     * dimension is asked of the provider (OpenAI "dimensions", Gemini "outputDimensionality"), so a model offering
     * more - gemini-embedding-001 gives 3072 - is entered with 1536 or less.
     */
    public static final int MAX_DIMENSION = 2000;

    static final int MIN_CONTEXT_LENGTH = 1024;
    static final int MAX_CONTEXT_LENGTH = 1_048_576;
    static final int MIN_OUTPUT_TOKENS = 256;
    static final int MAX_OUTPUT_TOKENS = 1_000_000;
    private static final int MAX_KEY_LENGTH = 4096;

    private final AiModelRepository repository;
    private final ModelServers servers;
    private final SecretCipher cipher;

    public AiModelService(AiModelRepository repository, ModelServers servers, SecretCipher cipher) {
        this.repository = repository;
        this.servers = servers;
        this.cipher = cipher;
    }

    public List<AiModel> findAll() {
        return repository.findAllOrderByKindAscAndDisplayNameAsc();
    }

    public Optional<AiModel> findById(Long id) {
        return repository.findById(id);
    }

    /**
     * 400 for invalid input, 409 if the provider (on this server) already has a model of this kind and name. A model
     * that transcribes or reads scans takes the place of the previous one (there is one at most of each).
     */
    @Transactional
    public AiModel create(AiModelRequest request) {
        validate(request);
        String baseUrl = baseUrl(request);
        assertAvailable(request, baseUrl, null);
        boolean transcribes = Boolean.TRUE.equals(request.transcribes());
        if (transcribes && !Boolean.TRUE.equals(request.readsAudio())) {
            throw ServiceException.badRequest("model.transcribeNeedsAudio",
                    "Only a model that reads audio can transcribe");
        }
        if (transcribes) {
            repository.clearTranscriber();
        }
        boolean readsScans = Boolean.TRUE.equals(request.readsScans());
        if (readsScans) {
            repository.clearScanReader();
        }
        ServerKey key = isBlank(request.apiKey()) ? ServerKey.NONE : newKey(request.apiKey());
        requireBedrockKey(request, key);
        Instant now = Instant.now();
        return repository.save(new AiModel(null, request.provider(), request.kind(), request.modelName().trim(),
                request.displayName().trim(), request.dimension(), request.inputPrice(), request.outputPrice(),
                request.active() == null || request.active(), Boolean.TRUE.equals(request.readsPdf()),
                Boolean.TRUE.equals(request.readsAudio()), transcribes,
                readsScans, request.takesTemperature() == null || request.takesTemperature(),
                Boolean.TRUE.equals(request.steersReasoning()), request.callsTools() == null || request.callsTools(),
                baseUrl, key.encrypted(), key.hint(),
                request.contextLength(), request.maxOutputTokens(), request.tierThreshold(), request.tierInputPrice(),
                request.tierOutputPrice(), request.cacheReadPrice(), request.cacheWritePrice(),
                request.tierCacheReadPrice(), request.tierCacheWritePrice(), now, now));
    }

    /**
     * The dimension of an embedding model a tenant uses cannot change: its embedding table has that vector length. A
     * new price applies from now on - recorded usage keeps the amount it was billed with. A server's key stays only
     * with its host: an address with another scheme, host or port drops it unless a new one comes along.
     */
    @Transactional
    public Optional<AiModel> update(Long id, AiModelRequest request) {
        Optional<AiModel> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        validate(request);
        String baseUrl = baseUrl(request);
        assertAvailable(request, baseUrl, id);
        AiModel model = existing.get();
        boolean inUse = repository.isEmbeddingModelOfATenant(id);
        if (inUse && (request.kind() != model.kind() || request.provider() != model.provider()
                || !request.modelName().trim().equals(model.modelName()) || !request.dimension().equals(model.dimension()))) {
            throw ServiceException.conflict("model.embeddingInUseChange",
                    "Tenants embed their documents with this model - only its name, prices, "
                    + "server and whether it is active can be changed");
        }
        boolean readsAudio = request.readsAudio() != null ? request.readsAudio() : model.readsAudio();
        boolean transcribes = request.transcribes() != null ? request.transcribes() : model.transcribes();
        if (transcribes && !readsAudio) {
            throw ServiceException.badRequest("model.transcribeNeedsAudio",
                    "Only a model that reads audio can transcribe");
        }
        if (transcribes && !model.transcribes()) {
            repository.clearTranscriber();
        }
        boolean readsScans = request.readsScans() != null ? request.readsScans() : model.readsScans();
        if (readsScans && !model.readsScans()) {
            repository.clearScanReader();
        }
        ServerKey key = updatedKey(model, request, baseUrl);
        requireBedrockKey(request, key);
        return Optional.of(repository.update(new AiModel(id, request.provider(), request.kind(),
                request.modelName().trim(), request.displayName().trim(), request.dimension(), request.inputPrice(),
                request.outputPrice(), request.active() != null ? request.active() : model.active(),
                request.readsPdf() != null ? request.readsPdf() : model.readsPdf(), readsAudio, transcribes, readsScans,
                request.takesTemperature() != null ? request.takesTemperature() : model.takesTemperature(),
                request.steersReasoning() != null ? request.steersReasoning() : model.steersReasoning(),
                request.callsTools() != null ? request.callsTools() : model.callsTools(), baseUrl, key.encrypted(),
                key.hint(), request.contextLength(), request.maxOutputTokens(), request.tierThreshold(),
                request.tierInputPrice(), request.tierOutputPrice(), request.cacheReadPrice(), request.cacheWritePrice(),
                request.tierCacheReadPrice(), request.tierCacheWritePrice(), model.createdAt(), Instant.now())));
    }

    /** 409 while a tenant embeds its documents with it - deactivate it instead. */
    @Transactional
    public boolean delete(Long id) {
        if (repository.isEmbeddingModelOfATenant(id)) {
            throw ServiceException.conflict("model.embeddingInUseDelete",
                    "Tenants embed their documents with this model - deactivate it instead");
        }
        if (repository.findById(id).isEmpty()) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    private static void validate(AiModelRequest request) {
        if (request == null || request.provider() == null || request.kind() == null) {
            throw ServiceException.badRequest("model.providerAndKindRequired", "Provider and kind are required");
        }
        if (isBlank(request.modelName()) || isBlank(request.displayName())) {
            throw ServiceException.badRequest("model.namesRequired", "Model name and display name are required");
        }
        if (!isPrice(request.inputPrice()) || !isPrice(request.outputPrice())) {
            throw ServiceException.badRequest("model.pricesInvalid", "Prices are required and must not be negative");
        }
        if (request.kind() != ModelKind.EMBEDDING && request.dimension() != null) {
            throw ServiceException.badRequest("model.dimensionOnlyEmbedding",
                    "Only an embedding model has a dimension");
        }
        boolean reads = Boolean.TRUE.equals(request.readsPdf()) || Boolean.TRUE.equals(request.readsAudio())
                || Boolean.TRUE.equals(request.transcribes())
                || Boolean.TRUE.equals(request.readsScans());
        if (request.kind() != ModelKind.CHAT && reads) {
            throw ServiceException.badRequest("model.filesOnlyChat", "Only a chat model reads files or transcribes");
        }
        // The chat's images go through langchain4j's Gemini chat model for now (docs/roadmap.md point 25); another
        // provider needs its own call in AiModels first.
        if (request.kind() == ModelKind.IMAGE && request.provider() != AiProvider.GEMINI) {
            throw ServiceException.badRequest("model.imageOnlyGemini",
                    "Only Gemini's image models can be used for now");
        }
        // Tavily has no models, and the web search no other provider (docs/architecture/ai-features.md "Web search").
        if ((request.kind() == ModelKind.WEB_SEARCH) != (request.provider() == AiProvider.TAVILY)) {
            throw ServiceException.badRequest("model.webSearchOnlyTavily",
                    "The web search is Tavily's, and Tavily offers only the web search");
        }
        // A reranker runs on an own server speaking TEI's /rerank, and such a server serves nothing else here
        // (docs/architecture/ai-features.md "Reranking").
        if ((request.kind() == ModelKind.RERANK) != (request.provider() == AiProvider.TEI)) {
            throw ServiceException.badRequest("model.rerankOnlyTei",
                    "A reranker runs on a TEI server, and a TEI server offers only the reranker");
        }

        validateServer(request);
        validateBedrock(request);

        validateMaxOutputTokens(request);
        validateTierAndCache(request);
        if (request.kind() == ModelKind.EMBEDDING) {
            if (request.provider() == AiProvider.ANTHROPIC) {
                throw ServiceException.badRequest("model.anthropicNoEmbedding", "Anthropic offers no embedding models");
            }
            if (request.dimension() == null || request.dimension() < 1 || request.dimension() > MAX_DIMENSION) {
                throw ServiceException.badRequest("model.dimensionOutOfRange",
                        "An embedding model needs a dimension between 1 and " + MAX_DIMENSION,
                        Map.of("max", String.valueOf(MAX_DIMENSION)));
            }
        }
    }

    /**
     * A tier is its threshold with both its prices - one without the other would price a long request at the wrong
     * rate without saying so. The cache prices are optional on their own, the tier's only with a tier.
     */
    private static void validateTierAndCache(AiModelRequest request) {
        for (BigDecimal price : Arrays.asList(request.tierInputPrice(), request.tierOutputPrice(),
                request.cacheReadPrice(), request.cacheWritePrice(), request.tierCacheReadPrice(),
                request.tierCacheWritePrice())) {
            if (price != null && price.signum() < 0) {
                throw ServiceException.badRequest("model.pricesInvalid", "Prices are required and must not be negative");
            }
        }
        boolean tierPrices = request.tierInputPrice() != null || request.tierOutputPrice() != null
                || request.tierCacheReadPrice() != null || request.tierCacheWritePrice() != null;
        if (request.tierThreshold() == null) {
            if (tierPrices) {
                throw ServiceException.badRequest("model.tierIncomplete",
                        "A tier's prices need the threshold above which they apply, and its input and output price");
            }
            return;
        }
        if (request.tierThreshold() < 1) {
            throw ServiceException.badRequest("model.tierThresholdInvalid", "The threshold is a number of tokens above 0");
        }
        if (request.tierInputPrice() == null || request.tierOutputPrice() == null) {
            throw ServiceException.badRequest("model.tierIncomplete",
                    "A tier's prices need the threshold above which they apply, and its input and output price");
        }
    }

    /** Only chat models answer; a limit below a short answer or above any model's output makes no sense. */
    private static void validateMaxOutputTokens(AiModelRequest request) {
        if (request.maxOutputTokens() == null) {
            return;
        }
        if (request.kind() != ModelKind.CHAT) {
            throw ServiceException.badRequest("model.outputTokensOnlyChat",
                    "Only a chat model has a limit of output tokens");
        }
        if (request.maxOutputTokens() < MIN_OUTPUT_TOKENS || request.maxOutputTokens() > MAX_OUTPUT_TOKENS) {
            throw ServiceException.badRequest("model.outputTokensOutOfRange", "The output tokens must be between "
                    + MIN_OUTPUT_TOKENS + " and " + MAX_OUTPUT_TOKENS,
                    Map.of("min", String.valueOf(MIN_OUTPUT_TOKENS), "max", String.valueOf(MAX_OUTPUT_TOKENS)));
        }
    }

    /** Address, key and context length belong to the models on own servers - and the context length to Ollama's chat models. */
    private static void validateServer(AiModelRequest request) {
        boolean ownServer = request.provider().hasOwnServer();
        if (!ownServer && (!isBlank(request.baseUrl()) || !isBlank(request.apiKey()))) {
            throw ServiceException.badRequest("model.serverOnlyOwn",
                    "Only a model on an own server has a server address and key - the "
                    + "providers' keys are kept under API-Keys");
        }
        if (request.contextLength() == null) {
            return;
        }
        if (request.provider() != AiProvider.OLLAMA || request.kind() != ModelKind.CHAT) {
            throw ServiceException.badRequest("model.contextLengthOnlyOllama",
                    "Only an Ollama chat model takes a context length");
        }
        if (request.contextLength() < MIN_CONTEXT_LENGTH || request.contextLength() > MAX_CONTEXT_LENGTH) {
            throw ServiceException.badRequest("model.contextLengthOutOfRange", "The context length must be between "
                    + MIN_CONTEXT_LENGTH + " and " + MAX_CONTEXT_LENGTH,
                    Map.of("min", String.valueOf(MIN_CONTEXT_LENGTH), "max", String.valueOf(MAX_CONTEXT_LENGTH)));
        }
    }

    /**
     * Amazon Bedrock (docs/architecture/ai-features.md "Amazon Bedrock"): its chat models only - the knowledge keeps its
     * own embedding model -, at the address of a region (the SDK signs every request for it), with an IAM access key.
     */
    private static void validateBedrock(AiModelRequest request) {
        if (request.provider() != AiProvider.BEDROCK) {
            return;
        }
        if (request.kind() != ModelKind.CHAT) {
            throw ServiceException.badRequest("model.bedrockOnlyChat", "Only Bedrock's chat models can be used");
        }
        if (!isBlank(request.baseUrl()) && BedrockClients.region(request.baseUrl().strip()).isEmpty()) {
            throw ServiceException.badRequest("model.bedrockAddressInvalid",
                    "A Bedrock address names its region: https://bedrock-runtime.<region>.amazonaws.com");
        }
        if (!isBlank(request.apiKey()) && !BedrockClients.isAccessKey(request.apiKey().strip())) {
            throw ServiceException.badRequest("model.bedrockKeyInvalid",
                    "A Bedrock key is an IAM access key: its id, a colon, its secret");
        }
    }

    /** A Bedrock model is asked with its access key only - also after a new address dropped the old one. */
    private static void requireBedrockKey(AiModelRequest request, ServerKey key) {
        if (request.provider() == AiProvider.BEDROCK && key.encrypted() == null) {
            throw ServiceException.badRequest("model.bedrockKeyRequired", "A Bedrock model needs its access key");
        }
    }

    private String baseUrl(AiModelRequest request) {
        return request.provider().hasOwnServer() ? servers.normalize(request.baseUrl()) : null;
    }

    private ServerKey updatedKey(AiModel model, AiModelRequest request, String baseUrl) {
        if (baseUrl == null || Boolean.TRUE.equals(request.removeApiKey())) {
            return ServerKey.NONE;
        }
        if (!isBlank(request.apiKey())) {
            return newKey(request.apiKey());
        }
        boolean sameServer = model.baseUrl() != null && ModelServers.origin(model.baseUrl()).equals(ModelServers.origin(baseUrl));
        return sameServer ? new ServerKey(model.apiKey(), model.keyHint()) : ServerKey.NONE;
    }

    private ServerKey newKey(String apiKey) {
        String key = apiKey.strip();
        if (key.length() > MAX_KEY_LENGTH || key.chars().anyMatch(Character::isWhitespace)) {
            throw ServiceException.badRequest("key.notApiKey", "This is not an API key");
        }
        // A short key would give away too much of itself in its hint.
        String hint = key.length() >= 12 ? "…" + key.substring(key.length() - 4) : "…";
        return new ServerKey(cipher.encrypt(key), hint);
    }

    private void assertAvailable(AiModelRequest request, String baseUrl, Long excludeId) {
        repository.findSame(request.provider(), request.kind(), request.modelName().trim(), baseUrl)
                .filter(conflict -> !conflict.id().equals(excludeId))
                .ifPresent(_ -> {
                    throw ServiceException.conflict("model.alreadyInCatalog", "This model is already in the catalog");
                });
    }

    /** A server's key, encrypted, with its hint - or none. */
    private record ServerKey(String encrypted, String hint) {
        static final ServerKey NONE = new ServerKey(null, null);
    }

    private static boolean isPrice(BigDecimal price) {
        return price != null && price.signum() >= 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
