package de.alpdesk.services.document;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.AiModelService;
import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.common.ServiceException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The search in a tenant's company knowledge (docs/architecture/ai-features.md "Search"): the chunks that answer a
 * query. The one place the search rules live - the chat's tool uses it, later the MCP server and workflows; the
 * retrieval measurement (RetrievalEvaluationSpec) measures exactly this. A search finds only the documents of the
 * asker's {@link KnowledgeScope}: the company knowledge and the areas assigned to them.
 * <p>
 * Hybrid: the vector search finds what means the same in other words ("Gewähr" - "Garantiezeit"), the full-text
 * search what is written exactly (codes, article numbers: "E23", "4711-B"). Both lists are merged by reciprocal rank
 * fusion: a chunk high in either list - or in both - comes first. A tenant with a reranker has the merged candidates
 * reordered by it (a cross-encoder reading question and chunk together) before the best are returned.
 */
@Singleton
public class KnowledgeSearch {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeSearch.class);

    /** The chunks one search returns unless asked for another number (the tenant's setting). */
    private static final int MAX_RESULTS = 5;
    /** The candidates each side brings to the merge - at least. */
    private static final int CANDIDATES = 20;
    /**
     * How close a vector candidate must be. The pgvector store's score is (cosine + 1) / 2: 0.6 asks for a cosine of at
     * least 0.2 - 0.5 would let through anything not opposite.
     */
    private static final double MIN_SCORE = 0.6;
    /** The most candidates the reranker reorders: all the hybrid search merges (20 per side). */
    static final int MAX_RERANK_CANDIDATES = 2 * CANDIDATES;
    /** The usual constant of reciprocal rank fusion: a rank r counts 1 / (60 + r). */
    private static final int RRF_K = 60;

    private final AiModelService models;
    private final ProviderKeyService keys;
    private final AiModels aiModels;
    private final TenantEmbeddings embeddings;
    private final UsageService usage;
    private final Mode mode;
    private final double textWeight;
    private final int rerankCandidates;

    /**
     * mode and textWeight: how the chat searches (app.search.*). Measured (RetrievalEvaluationSpec,
     * text-embedding-3-small, October 2026 - answering passage first / among the first five): VECTOR 56 % / 91 %;
     * HYBRID at text weight 1.0 62 % / 88 % (the many chunks sharing common words like "Kessel" or "NK" push good vector
     * hits down), 0.5 65 % / 94 %, 0.3 68 % / 94 % - exact codes gain fully (63 % to 88 % first); LANGCHAIN4J_HYBRID
     * like VECTOR (its full text needs all words of a question). rerankCandidates: how many of the merged candidates
     * the reranker reorders (app.search.rerank-candidates) - it takes about a second per candidate on a CPU, so a server
     * without a GPU gives it fewer (docs/architecture/ai-features.md "Reranking").
     */
    public KnowledgeSearch(AiModelService models, ProviderKeyService keys, AiModels aiModels, TenantEmbeddings embeddings,
                           UsageService usage, @Value("${app.search.mode}") Mode mode,
                           @Value("${app.search.text-weight}") double textWeight,
                           @Value("${app.search.rerank-candidates}") int rerankCandidates) {
        if (textWeight < 0) {
            throw new IllegalArgumentException("app.search.text-weight must not be negative");
        }
        if (rerankCandidates < 2 || rerankCandidates > MAX_RERANK_CANDIDATES) {
            throw new IllegalArgumentException("app.search.rerank-candidates must be between 2 and " + MAX_RERANK_CANDIDATES);
        }
        this.models = models;
        this.keys = keys;
        this.aiModels = aiModels;
        this.embeddings = embeddings;
        this.usage = usage;
        this.mode = mode;
        this.textWeight = textWeight;
        this.rerankCandidates = rerankCandidates;
    }

    /** How to search - the chat as configured (app.search.mode); the measurement compares them all. */
    public enum Mode {
        VECTOR,
        FULL_TEXT,
        HYBRID,
        /** langchain4j's own hybrid search: all words of the query must match in the full text. */
        LANGCHAIN4J_HYBRID
    }

    /**
     * The maxResults chunks the scope finds that answer the query best, best first, as configured - the chat's search
     * with the tenant's number of results. See {@link #search(Tenant, Long, KnowledgeScope, String, Mode)}.
     */
    public List<Hit> search(Tenant tenant, @Nullable Long userId, KnowledgeScope scope, String query, int maxResults) {
        return search(tenant, userId, scope, query, mode, textWeight, maxResults);
    }

    /**
     * The chunks the scope finds that answer the query, best first; empty without an embedding model. Embedding the
     * query - and reranking, if the tenant has a reranker - is recorded as the tenant's usage (userId: who asked, null
     * for the client software). A missing key (409) or a failing embedding provider (502) fails the search; a failing
     * reranker does not.
     */
    public List<Hit> search(Tenant tenant, @Nullable Long userId, KnowledgeScope scope, String query, Mode mode) {
        return search(tenant, userId, scope, query, mode, textWeight, MAX_RESULTS);
    }

    /** As {@link #search(Tenant, Long, KnowledgeScope, String, Mode)} with another weight of the full text - for the measurement. */
    public List<Hit> search(Tenant tenant, @Nullable Long userId, KnowledgeScope scope, String query, Mode mode,
                            double textWeight) {
        return search(tenant, userId, scope, query, mode, textWeight, MAX_RESULTS);
    }

    private List<Hit> search(Tenant tenant, @Nullable Long userId, KnowledgeScope scope, String query, Mode mode,
                             double textWeight, int maxResults) {
        AiModel model = tenant.embeddingModelId() == null ? null : models.findById(tenant.embeddingModelId()).orElse(null);
        if (model == null) {
            return List.of();
        }
        AiModel reranker = reranker(tenant);
        int found = reranker == null ? maxResults : Math.max(rerankCandidates, maxResults);
        int candidates = Math.max(CANDIDATES, maxResults);
        List<Hit> hits = switch (mode) {
            case VECTOR -> searchVectors(tenant, userId, scope, model, query, found);
            case FULL_TEXT -> searchText(tenant, scope, query, found);
            case HYBRID -> fuse(searchVectors(tenant, userId, scope, model, query, candidates),
                    searchText(tenant, scope, query, candidates), textWeight, found);
            case LANGCHAIN4J_HYBRID -> searchLangchain4jHybrid(tenant, userId, scope, model, query, found);
        };
        return reranker == null ? hits : rerank(tenant, userId, reranker, query, hits, maxResults);
    }

    /** The tenant's reranker - none when it chose none or the admin deactivated it. */
    private @Nullable AiModel reranker(Tenant tenant) {
        if (tenant.rerankingModelId() == null) {
            return null;
        }
        return models.findById(tenant.rerankingModelId()).filter(AiModel::active).orElse(null);
    }

    /**
     * The best maxResults of the candidates in the reranker's order, its call recorded as the tenant's usage (tokens
     * estimated - TEI counts none). A failing reranker never fails the search: the hybrid order is kept, and the log
     * says so.
     */
    private List<Hit> rerank(Tenant tenant, Long userId, AiModel reranker, String query, List<Hit> hits, int maxResults) {
        if (hits.size() < 2) {
            return hits;
        }
        List<String> texts = hits.stream().map(Hit::text).toList();
        ResolvedKey key = keys.resolve(tenant, reranker);
        List<Double> scores;
        try {
            scores = aiModels.rerank(reranker, key.apiKey(), query, texts);
        } catch (ServiceException e) {
            LOG.warn("Reranking for tenant {} failed, the search keeps its own order: {}", tenant.id(), e.getMessage());
            return hits.subList(0, Math.min(maxResults, hits.size()));
        }
        usage.record(tenant.id(), userId, reranker, key.source(), null,
                query.length() * (long) texts.size() + texts.stream().mapToLong(String::length).sum());
        List<Integer> order = new ArrayList<>(IntStream.range(0, hits.size()).boxed().toList());
        order.sort(Comparator.comparing(scores::get).reversed());
        return order.stream().limit(maxResults).map(hits::get).toList();
    }

    private List<Hit> searchVectors(Tenant tenant, Long userId, KnowledgeScope scope, AiModel model, String query,
                                    int limit) {
        Embedding embedded = embedQuery(tenant, userId, model, query);
        return hits(embeddings.searchVectors(tenant.databaseName(), embedded.vector(), MIN_SCORE, limit, scope));
    }

    /**
     * langchain4j's own hybrid search, for the measurement: its store knows neither areas nor documents, so what it found
     * is filtered by the READY documents the scope finds afterwards - fewer hits than asked for when some drop out.
     */
    private List<Hit> searchLangchain4jHybrid(Tenant tenant, Long userId, KnowledgeScope scope, AiModel model,
                                              String query, int maxResults) {
        Embedding embedded = embedQuery(tenant, userId, model, query);
        Set<Long> found = embeddings.documentIdsFound(tenant.databaseName(), scope);
        // Its score is the fused one (a few hundredths): no minimum.
        List<Hit> hits = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : embeddings.langchain4jHybridStore(tenant.databaseName(), model.dimension(),
                tenant.searchLanguage())
                .search(EmbeddingSearchRequest.builder().queryEmbedding(embedded).query(query)
                        .maxResults(maxResults).minScore(0.0).build())
                .matches()) {
            Long documentId = match.embedded().metadata().getLong(TenantEmbeddings.DOCUMENT_ID);
            if (found.contains(documentId)) {
                hits.add(new Hit(match.embeddingId(), documentId, 0,
                        match.embedded().metadata().getString(TenantEmbeddings.FILE_NAME), match.embedded().text(),
                        match.score()));
            }
        }
        return hits;
    }

    private List<Hit> searchText(Tenant tenant, KnowledgeScope scope, String query, int limit) {
        return hits(embeddings.searchText(tenant.databaseName(), tenant.searchLanguage(), query, limit, scope));
    }

    /** The query's vector - recorded as the tenant's usage. */
    private Embedding embedQuery(Tenant tenant, Long userId, AiModel model, String query) {
        ResolvedKey key = keys.resolve(tenant, model);
        Response<List<Embedding>> embedded = aiModels.embed(model, key.apiKey(), List.of(TextSegment.from(query)));
        usage.record(tenant.id(), userId, model, key.source(), embedded.tokenUsage(), query.length());
        return embedded.content().getFirst();
    }

    private static List<Hit> hits(List<TenantEmbeddings.ChunkMatch> matches) {
        List<Hit> hits = new ArrayList<>();
        for (TenantEmbeddings.ChunkMatch match : matches) {
            hits.add(new Hit(match.id(), match.documentId(), match.documentChunks(), match.fileName(), match.text(),
                    match.score()));
        }
        return hits;
    }

    /**
     * Weighted reciprocal rank fusion of both lists; a chunk keeps its vector score (0 if only the full text found it).
     */
    private static List<Hit> fuse(List<Hit> vectorHits, List<Hit> textHits, double textWeight, int maxResults) {
        Map<String, Hit> byId = new LinkedHashMap<>();
        Map<String, Double> fused = new LinkedHashMap<>();
        addRanks(vectorHits, 1.0, byId, fused);
        addRanks(textHits, textWeight, byId, fused);
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(maxResults)
                .map(entry -> byId.get(entry.getKey()))
                .toList();
    }

    private static void addRanks(List<Hit> hits, double weight, Map<String, Hit> byId, Map<String, Double> fused) {
        for (int rank = 1; rank <= hits.size(); rank++) {
            Hit hit = hits.get(rank - 1);
            byId.putIfAbsent(hit.id(), hit);
            fused.merge(hit.id(), weight / (RRF_K + rank), Double::sum);
        }
    }

    /**
     * A chunk found: its id, its document's id, how many chunks the document has (0: not known - langchain4j's own
     * search), its file name (with its folder), its text and its vector score (0..1; 0: by full text only).
     */
    public record Hit(String id, Long documentId, int documentChunks, String fileName, String text, double score) {
    }
}
