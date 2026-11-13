package de.alpdesk.services.document;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.document.Document;
import de.alpdesk.entities.document.DocumentStatus;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.document.DocumentRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.ai.AiModelService;
import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.TenantDatabases;
import de.alpdesk.storage.FileStorage;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Embeds documents in the background (executor "indexing"): lets the image model read a document's images and scanned
 * pages first ({@link ScanReader}), splits the text into chunks, embeds them with the tenant's
 * embedding model and stores them in its table "embedding". A tenant's documents are embedded one after the other,
 * under {@link #withTenantLock}, which a change of the embedding model takes too - so no document is ever stored with
 * the vectors of the previous model. Documents still waiting when the process stopped are taken up again on start.
 * <p>
 * The tenants take turns (docs/architecture/knowledge-sources.md "The indexer, fair between tenants"): each has a queue
 * of its own, and the executor holds at most one job per tenant, which embeds its next document and queues itself again
 * at the end - so one tenant's import of thousands of files holds up another tenant by one document, not by all of
 * them, and no thread waits on a tenant's lock. Within a tenant the order stays as queued.
 */
@Singleton
public class DocumentIndexer implements ApplicationEventListener<ServerStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentIndexer.class);
    /** Characters per chunk and overlap between neighbouring chunks. */
    private static final int CHUNK_SIZE = 1000;
    private static final int CHUNK_OVERLAP = 100;
    /** Chunks per embedding call - below every provider's limit per request. */
    private static final int BATCH_SIZE = 64;
    private static final int MAX_TITLE = 120;
    /** The markup of Markdown and plain text a title line may be framed with: headings, bold, quotes, lists, tables. */
    private static final String MARKUP = "#*>-+|_~=`•";
    /** A full-text index build that failed is tried again this often, this long apart. */
    static final int INDEX_ATTEMPTS = 5;
    static final Duration INDEX_RETRY_DELAY = Duration.ofMinutes(1);

    private final TenantRepository tenants;
    private final DocumentRepository documents;
    private final TenantDatabases databases;
    private final TenantEmbeddings embeddings;
    private final AiModelService modelService;
    private final ProviderKeyService keys;
    private final AiModels aiModels;
    private final UsageService usage;
    private final ExecutorService executor;
    /** Times the next attempt of a failed index build - like AiModels' watchdog, a thread of its own. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("index-retry").factory());
    /** The specs shorten it. */
    private Duration indexRetryDelay = INDEX_RETRY_DELAY;
    private final TenantModelService tenantModels;
    private final ScanReader scanReader;
    private final FileStorage storage;
    private final Map<Long, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();
    /** The documents waiting per tenant; a tenant is in it exactly while its job is in the executor. Guarded by itself. */
    private final Map<Long, Deque<Long>> waiting = new HashMap<>();

    public DocumentIndexer(TenantRepository tenants, DocumentRepository documents, TenantDatabases databases,
                           TenantEmbeddings embeddings, AiModelService modelService, ProviderKeyService keys,
                           AiModels aiModels, UsageService usage, @Named("indexing") ExecutorService executor,
                           TenantModelService tenantModels, ScanReader scanReader, FileStorage storage) {
        this.tenants = tenants;
        this.documents = documents;
        this.databases = databases;
        this.embeddings = embeddings;
        this.modelService = modelService;
        this.keys = keys;
        this.aiModels = aiModels;
        this.usage = usage;
        this.executor = executor;
        this.tenantModels = tenantModels;
        this.scanReader = scanReader;
        this.storage = storage;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Queues the document behind the tenant's others; the tenant's job is started if it has none in the executor. */
    public void submit(Long tenantId, Long documentId) {
        synchronized (waiting) {
            Deque<Long> queue = waiting.get(tenantId);
            if (queue != null) {
                queue.add(documentId);
                return;
            }
            waiting.put(tenantId, new ArrayDeque<>(List.of(documentId)));
        }
        executor.execute(() -> next(tenantId));
    }

    /** Embeds the tenant's next document, then lets the other tenants' jobs come first. */
    private void next(Long tenantId) {
        Long documentId;
        synchronized (waiting) {
            documentId = waiting.get(tenantId).poll();
        }
        try {
            withTenantLock(tenantId, () -> {
                try {
                    index(tenantId, documentId);
                } catch (RuntimeException e) {
                    // Beyond index's own handling (the tenant database out of reach, its state FAILED not written):
                    // the document keeps its state until a retry or the next start - logged here, or it reached only
                    // stderr.
                    LOG.error("Document {} of tenant {} could not be embedded and keeps its state: {}", documentId,
                            tenantId, e.toString());
                }
                return null;
            });
        } finally {
            // Whatever happened, the tenant's other documents must not stay waiting without a job.
            boolean more;
            synchronized (waiting) {
                more = !waiting.get(tenantId).isEmpty();
                if (!more) {
                    waiting.remove(tenantId);
                }
            }
            if (more) {
                executor.execute(() -> next(tenantId));
            }
        }
    }

    /**
     * Makes the tenant's full-text index the one for its language - after the language changed, and on start for
     * every tenant - in the background (a tenant with many chunks takes seconds, and nothing should wait for it: the
     * search runs without the index meanwhile, right and slower) and under the tenant's lock: the indexer may be
     * creating the table with the index of the language it read before. The language is read again here, so a second
     * change in between ends with the last one. A build that fails (a transaction held the table past
     * TenantEmbeddings.INDEX_LOCK_TIMEOUT, the server out of reach) is tried again after {@link #INDEX_RETRY_DELAY},
     * {@link #INDEX_ATTEMPTS} times in all - then it is an error for the admin's log, and the next start tries anew.
     * A tenant deleted meanwhile ends it quietly.
     */
    public void ensureTextIndex(Long tenantId) {
        ensureTextIndex(tenantId, 1);
    }

    private void ensureTextIndex(Long tenantId, int attempt) {
        executor.execute(() -> buildTextIndex(tenantId, attempt));
    }

    /**
     * Every tenant's full-text index on start, one tenant after the other: the job checks one tenant and queues itself
     * again for the next - as a tenant's documents do. One job per tenant at once would fill the executor's queue, and
     * a document uploaded right after the start waited behind all of them (with a thousand tenants, longer than a
     * user waits); so it waits behind one check. A server shutting down ends the walk - the next start walks again.
     */
    private void ensureTextIndexes(Deque<Long> tenantIds) {
        Long tenantId = tenantIds.poll();
        if (tenantId == null) {
            return;
        }
        try {
            executor.execute(() -> {
                buildTextIndex(tenantId, 1);
                ensureTextIndexes(tenantIds);
            });
        } catch (RejectedExecutionException e) {
            LOG.debug("The full-text indexes of {} tenants are left to the next start - the server is shutting down",
                    tenantIds.size() + 1);
        }
    }

    private void buildTextIndex(Long tenantId, int attempt) {
        withTenantLock(tenantId, () -> {
            Optional<Tenant> tenant = tenants.findById(tenantId);
            if (tenant.isEmpty()) {
                return null;
            }
            try {
                embeddings.ensureTextIndex(tenant.get().databaseName(), tenant.get().searchLanguage());
            } catch (RuntimeException e) {
                if (attempt < INDEX_ATTEMPTS) {
                    // The cause says why (the wait for a lock over, the database gone); the exception only which tenant.
                    LOG.warn("The full-text index of tenant {} could not be built (attempt {} of {}) - tried again in {}: {}",
                            tenantId, attempt, INDEX_ATTEMPTS, indexRetryDelay,
                            e.getCause() != null ? e.getCause().toString() : e.toString());
                    scheduler.schedule(() -> ensureTextIndex(tenantId, attempt + 1), indexRetryDelay.toMillis(),
                            TimeUnit.MILLISECONDS);
                } else {
                    LOG.error("The full-text index of tenant {} could not be built in {} attempts - the next start tries anew",
                            tenantId, INDEX_ATTEMPTS, e);
                }
            }
            return null;
        });
    }

    /** Runs the action while no document of the tenant is being embedded. */
    public <T> T withTenantLock(Long tenantId, Supplier<T> action) {
        ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, _ -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes up what the last run left unfinished, and makes every tenant's full-text index the one for its language
     * (a look at the catalog, a build once after the language became a setting) - on the server's start, so after
     * {@code TenantDatabaseMigration} (on the context's start) brought every tenant database to the current schema,
     * and in the background: the start does not wait for an index. Every tenant is read through a connection of its
     * own, closed at once -
     * not its pool, which would keep a connection open for half a minute for every tenant and on a start with hundreds
     * of tenants take the server's last ones. Only a tenant with work to do opens its pool, when it is embedded.
     */
    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        List<Tenant> all = tenants.findAllOrderByName();
        ensureTextIndexes(new ArrayDeque<>(all.stream().map(Tenant::id).toList()));
        for (Tenant tenant : all) {
            if (tenant.embeddingModelId() == null) {
                continue;
            }
            try (Connection connection = databases.openConnection(tenant.databaseName())) {
                documents.findUnfinishedIds(connection).forEach(documentId -> submit(tenant.id(), documentId));
            } catch (SQLException | RuntimeException e) {
                LOG.error("Unfinished documents of tenant {} could not be taken up", tenant.id(), e);
            }
        }
    }

    private void index(Long tenantId, Long documentId) {
        Optional<Tenant> tenant = tenants.findById(tenantId);
        if (tenant.isEmpty()) {
            return;
        }
        DataSource dataSource = databases.dataSource(tenant.get().databaseName());
        Optional<Document> document = documents.findById(dataSource, documentId);
        if (document.isEmpty() || document.get().status() == DocumentStatus.READY) {
            // Ready: queued twice (a model change while it waited) and done by the first run - not embedded and billed again.
            return;
        }
        AiModel model = null;
        try {
            model = modelService.findById(tenant.get().embeddingModelId())
                    .orElseThrow(() -> ServiceException.conflict("document.noEmbeddingModel",
                            "No embedding model chosen"));
            documents.updateStatus(dataSource, documentId, DocumentStatus.PROCESSING, null, 0);
            if (document.get().readsImages() && !readImages(tenant.get(), dataSource, document.get())) {
                // Replaced while it was read: the new version's own run reads and embeds it.
                return;
            }
            Optional<String> text = documents.findText(dataSource, documentId, document.get().contentHash());
            if (text.isEmpty()) {
                // Replaced (or deleted) since it was looked up: the new version's run embeds that one's text.
                return;
            }
            int chunks = embed(tenant.get(), model, documentId, document.get().path(), text.get());
            if (documents.findById(dataSource, documentId).isEmpty()) {
                // Deleted meanwhile: what was just stored belongs to no document.
                embeddings.removeDocument(tenant.get().databaseName(), model.dimension(), documentId);
                return;
            }
            // Replaced while it was embedded (a push): its new version waits for its own run, which removes these chunks.
            documents.markReady(dataSource, documentId, document.get().contentHash(), chunks);
        } catch (RuntimeException e) {
            LOG.warn("Document {} of tenant {} could not be embedded: {}", documentId, tenantId, e.toString());
            if (model != null) {
                removeQuietly(tenant.get(), model, documentId);
            }
            String error = e instanceof ServiceException ? e.getMessage() : "Embedding failed - please try again";
            documents.updateStatus(dataSource, documentId, DocumentStatus.FAILED, error, 0);
        }
    }

    /**
     * Lets the image model read the document's images and scanned pages; its text replaces Tika's, and the original
     * file - kept only for this - is removed. Read once: embedding it again later (another model, a retry after a
     * failed embedding) does not read it again. False when the document was replaced while it was read (a page takes a
     * model call, a scan minutes): the reading of the old version is dropped and the new version's original kept - the
     * new version's own run reads it. Failed (409) when the stored file is not the row's version.
     */
    private boolean readImages(Tenant tenant, DataSource dataSource, Document document) {
        AiModel reader = tenantModels.findScanReader(tenant.id())
                .orElseThrow(() -> ServiceException.conflict("document.noScanModel",
                        "No model reads images and scans for this tenant - the "
                        + "admin chooses one in the model catalog"));
        ResolvedKey key = keys.resolve(tenant, reader);
        String originalKey = DocumentService.originalKey(tenant.id(), document.id());
        byte[] original = storage.open(originalKey).map(DocumentIndexer::readAll)
                .orElseThrow(() -> ServiceException.conflict("document.originalMissing",
                        "The original file is missing - please upload it again"));
        if (!DocumentService.sha256(original).equals(document.contentHash())) {
            // The row is already the new version's, its file not yet (stored right after the row): reading this file
            // would save the previous version's text under the new version's hash. Failed, not left waiting: two
            // replaces at once can store the files in the other order, and the row would wait for a file that never
            // comes - a run of the new file after it reads it anyway (only READY is skipped), a push of it heals it.
            throw ServiceException.conflict("document.originalChanged",
                    "The document was replaced while it was read - please upload it again");
        }
        String text = scanReader.read(tenant, reader, key, document.contentType(), document.fileName(), original);
        if (text.isEmpty()) {
            throw ServiceException.badRequest("document.scanFoundNothing",
                    "The image model found no text or picture in the document");
        }
        if (!documents.saveReading(dataSource, document.id(), document.contentHash(), text)) {
            return false;
        }
        storage.delete(originalKey);
        return true;
    }

    private static byte[] readAll(InputStream content) {
        try (content) {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Embeds and stores the chunks, records the usage, and answers how many chunks there were. */
    private int embed(Tenant tenant, AiModel model, Long documentId, String path, String text) {
        ResolvedKey key = keys.resolve(tenant, model);
        String header = contextHeader(path, text);
        List<TextSegment> segments = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP)
                .split(dev.langchain4j.data.document.Document.from(text)).stream()
                .map(segment -> TextSegment.from(header + segment.text(),
                        Metadata.from(Map.of(TenantEmbeddings.DOCUMENT_ID, documentId, TenantEmbeddings.FILE_NAME, path))))
                .toList();
        EmbeddingStore<TextSegment> store = embeddings.storeForIndexing(tenant.databaseName(), model.dimension(),
                tenant.searchLanguage());
        // A document embedded again (new model, a retry) must not keep its old chunks next to the new ones.
        embeddings.removeDocument(tenant.databaseName(), model.dimension(), documentId);
        TokenUsage total = null;
        for (int start = 0; start < segments.size(); start += BATCH_SIZE) {
            List<TextSegment> batch = segments.subList(start, Math.min(start + BATCH_SIZE, segments.size()));
            Response<List<Embedding>> response = aiModels.embed(model, key.apiKey(), batch);
            store.addAll(response.content(), batch);
            total = total == null ? response.tokenUsage() : TokenUsage.sum(total, response.tokenUsage());
        }
        usage.record(tenant.id(), null, model, key.source(), total, text.length());
        return segments.size();
    }

    /**
     * The context every chunk of the document starts with: its path (folder and file name) and its title (the first
     * line with a letter or a digit, without the markup around it, at most {@value #MAX_TITLE} characters - the image
     * model's page markers do not count). A chunk deep in a document - a row of a technical data table - otherwise does
     * not say what it is about ("Gewicht leer 38 kg" of which boiler?); with it, the vector and the full-text search
     * find it by the document's subject too, and the chat model reads where a passage comes from.
     * <p>
     * A line without a letter or a digit is no title: a Markdown file starting with front matter had "---" as the title
     * of every chunk (October 2026), and so would a rule, a table's separator or a line of emojis.
     */
    static String contextHeader(String path, String text) {
        String title = text.lines().map(String::strip)
                .filter(line -> !line.startsWith(ScanReader.PAGE_MARKER))
                .map(DocumentIndexer::withoutMarkup)
                .filter(DocumentIndexer::hasLetterOrDigit)
                .findFirst().orElse("");
        if (title.length() > MAX_TITLE) {
            title = title.substring(0, MAX_TITLE).strip() + "…";
        }
        return (title.isEmpty() ? path : path + " – " + title) + "\n\n";
    }

    /**
     * The line without the markup around it: "### 📊 Specifications" - "Specifications", "**Manual**" - "Manual".
     * Symbols such as emojis go at the start only; punctuation belonging to the text ("Why?", "(draft)") stays.
     */
    static String withoutMarkup(String line) {
        int start = 0;
        while (start < line.length() && isLeadingMarkup(line.codePointAt(start))) {
            start += Character.charCount(line.codePointAt(start));
        }
        int end = line.length();
        while (end > start && isMarkup(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(start, end);
    }

    private static boolean isLeadingMarkup(int codePoint) {
        if (isMarkup(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        // Emojis and arrows; the marks and joiners emojis are built with; a byte order mark.
        return type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL || type == Character.NON_SPACING_MARK
                || type == Character.FORMAT;
    }

    private static boolean isMarkup(int codePoint) {
        return Character.isWhitespace(codePoint) || MARKUP.indexOf(codePoint) >= 0;
    }

    private static boolean hasLetterOrDigit(String line) {
        return line.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private void removeQuietly(Tenant tenant, AiModel model, Long documentId) {
        try {
            embeddings.removeDocument(tenant.databaseName(), model.dimension(), documentId);
        } catch (RuntimeException e) {
            LOG.warn("Chunks of document {} could not be removed: {}", documentId, e.toString());
        }
    }
}
