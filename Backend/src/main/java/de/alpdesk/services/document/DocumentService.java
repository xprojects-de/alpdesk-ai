package de.alpdesk.services.document;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.document.Document;
import de.alpdesk.entities.document.DocumentOrigin;
import de.alpdesk.entities.document.DocumentStatus;
import de.alpdesk.entities.tenant.SearchLanguage;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.document.DocumentRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.ai.AiModelService;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.TenantDatabases;
import de.alpdesk.services.tenant.TenantService;
import de.alpdesk.storage.FileStorage;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A tenant's documents - the company's knowledge every user finds in the chat, or an area's that only the users and keys
 * assigned to it find ({@link KnowledgeAreaService}): uploaded by the admin, a tenant admin (frontend, one by one or as a
 * ZIP archive) or the client software (/api/v1). Upload extracts the text
 * ({@link DocumentTextExtractor}) and stores it; images and scanned pages are read by the image model and everything is
 * embedded in the background ({@link DocumentIndexer}, {@link ScanReader}).
 */
@Singleton
public class DocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);

    public static final int MAX_EXTERNAL_ID = 200;
    /** How {@link #deleteUploads} names the company knowledge - the documents without an area. */
    public static final String COMPANY_KNOWLEDGE = "company";
    /** One document - an upload, a push or an entry of a ZIP archive. */
    public static final int MAX_DOCUMENT_BYTES = 50 * 1024 * 1024;

    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final DocumentRepository documents;
    private final DocumentTextExtractor extractor;
    private final AiModelService models;
    private final TenantModelService tenantModels;
    private final ProviderKeyService keys;
    private final TenantEmbeddings embeddings;
    private final DocumentIndexer indexer;
    private final FileStorage storage;
    private final KnowledgeAreaService areas;

    public DocumentService(TenantRepository tenants, TenantDatabases databases, DocumentRepository documents,
                           DocumentTextExtractor extractor, AiModelService models, TenantModelService tenantModels,
                           ProviderKeyService keys, TenantEmbeddings embeddings, DocumentIndexer indexer,
                           FileStorage storage, KnowledgeAreaService areas) {
        this.tenants = tenants;
        this.databases = databases;
        this.documents = documents;
        this.extractor = extractor;
        this.models = models;
        this.tenantModels = tenantModels;
        this.keys = keys;
        this.embeddings = embeddings;
        this.indexer = indexer;
        this.storage = storage;
        this.areas = areas;
    }

    public List<Document> findAll(Long tenantId) {
        return documents.findAll(dataSource(tenant(tenantId)));
    }

    /** The documents someone with this scope finds - the client software's list. */
    public List<Document> findAll(Long tenantId, KnowledgeScope scope) {
        List<Document> visible = new ArrayList<>();
        for (Document document : findAll(tenantId)) {
            if (scope.includes(document.areaId())) {
                visible.add(document);
            }
        }
        return visible;
    }

    /**
     * Stores a document uploaded in the frontend and queues it for embedding. userId: the uploading tenant admin, null
     * for the admin; areaId: null for the company knowledge, 400 for an area that is not the tenant's. Refused as
     * described at {@link #extract}.
     */
    public Document upload(Long tenantId, Long userId, @Nullable Long areaId, String fileName, byte[] content) {
        Tenant tenant = tenant(tenantId);
        Long area = areas.requireArea(tenantId, areaId);
        requireEmbedding(tenant);
        DocumentRepository.NewDocument document = extract(tenant, fileName, "", content, userId, DocumentOrigin.UPLOAD,
                null, area, null);
        return queued(tenant, documents.insert(dataSource(tenant), document), document, content);
    }

    /**
     * Imports a ZIP archive of documents uploaded in the frontend (docs/architecture/ai-features.md "Importing a ZIP
     * archive"): every file becomes a document under its path in the area (null: the company knowledge), its folder
     * kept. Imported again into the same area, a path already there is replaced when its content changed and left as it
     * is otherwise; documents missing from the archive stay ({@link #deleteUploads} empties the area first). A file
     * under a new path whose content is already in the area is not stored a second time; a file that cannot be read is
     * skipped - both are reported. 409 without an embedding model, 400 for a broken archive or an unknown area.
     */
    public ArchiveImport importArchive(Long tenantId, Long userId, @Nullable Long areaId, Path zip) {
        Tenant tenant = tenant(tenantId);
        Long area = areas.requireArea(tenantId, areaId);
        requireEmbedding(tenant);
        DataSource dataSource = dataSource(tenant);
        List<ImportedFile> files = new ArrayList<>();
        DocumentArchive.read(zip, new DocumentArchive.Handler() {
            @Override
            public void file(String path, byte[] content) {
                files.add(importFile(tenant, dataSource, userId, area, path, content));
            }

            @Override
            public void skipped(String path, String reason) {
                files.add(new ImportedFile(path, ImportOutcome.SKIPPED, null, reason));
            }
        });
        return new ArchiveImport(files);
    }

    private ImportedFile importFile(Tenant tenant, DataSource dataSource, Long userId, @Nullable Long areaId, String path,
                                    byte[] content) {
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "" : path.substring(0, slash);
        DocumentRepository.NewDocument document;
        try {
            document = extract(tenant, path.substring(slash + 1), folder, content, userId, DocumentOrigin.UPLOAD, path,
                    areaId, null);
        } catch (ServiceException e) {
            return new ImportedFile(path, ImportOutcome.SKIPPED, null, e.getMessage());
        }
        // Only a new path can be a duplicate: a path already there whose new content equals another file's is replaced -
        // left as it was, its old version would still be found although the archive no longer holds it.
        if (documents.findImported(dataSource, areaId, path).isEmpty()) {
            Optional<Document> same = documents.findSameContent(dataSource, areaId, document.contentHash(), path);
            if (same.isPresent()) {
                return new ImportedFile(path, ImportOutcome.DUPLICATE, same.get().id(), same.get().path());
            }
        }
        Optional<DocumentRepository.Upserted> upserted = documents.upsert(dataSource, document);
        if (upserted.isEmpty()) {
            Long unchanged = documents.findImported(dataSource, areaId, path).map(Document::id).orElse(null);
            return new ImportedFile(path, ImportOutcome.UNCHANGED, unchanged, null);
        }
        queued(tenant, upserted.get().document(), document, content);
        return new ImportedFile(path, upserted.get().inserted() ? ImportOutcome.CREATED : ImportOutcome.REPLACED,
                upserted.get().document().id(), null);
    }

    /**
     * Stores a document the client software pushes (docs/architecture/ai-features.md "Documents pushed by the client
     * software"). With an externalId (its own key) a known document is replaced in place - same id, embedded again -
     * and an unchanged one is left as it is, without embedding (no cost); without one every push is a new document.
     * areaName: the area it goes into - null (not sent): a known document keeps its area, a new one is company
     * knowledge; blank: the company knowledge; a name: that area, a known document moves there. A key writes only where
     * it reads (scope): 400 for an unknown area or one the key is not assigned to, 409 for a known document in an area
     * the key does not find - checked in the statement that writes, so a document moved meanwhile stays where it is.
     * 400 for an externalId that is blank or longer than {@value #MAX_EXTERNAL_ID}; else refused as at {@link #extract}.
     * No transaction: every write is one statement that checks what it needs itself.
     */
    public Pushed push(Long tenantId, KnowledgeScope scope, @Nullable String areaName, @Nullable String externalId,
                       String fileName, byte[] content) {
        Tenant tenant = tenant(tenantId);
        boolean keepsArea = areaName == null;
        Long areaId = areas.requireAreaNamed(tenantId, areaName);
        if (!scope.includes(areaId)) {
            throw ServiceException.badRequest("document.areaNotAssigned", "This API key is not assigned to the area "
                    + areaName.strip(),
                    Map.of("area", areaName.strip()));
        }
        requireEmbedding(tenant);
        String key = externalId(externalId);
        DocumentRepository.NewDocument document = extract(tenant, fileName, "", content, null, DocumentOrigin.API, key,
                areaId, null);
        DataSource dataSource = dataSource(tenant);
        if (key == null) {
            return new Pushed(queued(tenant, documents.insert(dataSource, document), document, content), PushOutcome.CREATED);
        }
        List<Long> allowedAreaIds = scope.findsEverything() ? null : scope.areaIdList();
        Optional<DocumentRepository.Upserted> upserted = documents.upsertPushed(dataSource, document, keepsArea,
                allowedAreaIds);
        if (upserted.isPresent()) {
            return new Pushed(queued(tenant, upserted.get().document(), document, content),
                    upserted.get().inserted() ? PushOutcome.CREATED : PushOutcome.REPLACED);
        }
        // Not written: its text is unchanged, or it lies in an area the key does not find.
        Document known = documents.findPushed(dataSource, key).orElseThrow(DocumentService::changedMeanwhile);
        if (!scope.includes(known.areaId())) {
            throw outsideScope();
        }
        if (!Objects.equals(known.contentHash(), document.contentHash())) {
            // Refused for its area, then moved where the key finds it: the new text was not written.
            throw changedMeanwhile();
        }
        if (keepsArea || Objects.equals(known.areaId(), areaId)) {
            return new Pushed(known, PushOutcome.UNCHANGED);
        }
        // Its text is the same: only the area changes - its chunks stay, the search reads the area from the document.
        boolean moved = allowedAreaIds == null ? documents.updateArea(dataSource, known.id(), areaId)
                : documents.updateArea(dataSource, known.id(), areaId, allowedAreaIds);
        if (!moved) {
            throw outsideScope();
        }
        return new Pushed(documents.findById(dataSource, known.id()).orElseThrow(), PushOutcome.UNCHANGED);
    }

    private static ServiceException changedMeanwhile() {
        return ServiceException.conflict("document.changedMeanwhile",
                "The document was changed meanwhile - please push it again");
    }

    private static ServiceException outsideScope() {
        return ServiceException.conflict("document.externalIdOtherArea",
                "The document with this externalId belongs to an area this API key is not "
                + "assigned to");
    }

    /**
     * Stores a file of a knowledge source (docs/architecture/knowledge-sources.md) under its path relative to the
     * source's folder: a new file becomes a document of the area (null: the company knowledge); a file whose document is
     * known (documentId) replaces it in place - same id, its area as it is now (moved by hand: stays moved) - unless its
     * text is unchanged, then nothing happens (no embedding, no cost). A document deleted meanwhile is stored anew.
     * Refused as described at {@link #extract} - the source records the code. No transaction: every write is one
     * statement on the tenant database.
     */
    public Pushed fromSource(Long tenantId, Long sourceId, @Nullable Long areaId, String path, @Nullable Long documentId,
                             byte[] content) {
        Tenant tenant = tenant(tenantId);
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "" : path.substring(0, slash);
        DocumentRepository.NewDocument document = extract(tenant, path.substring(slash + 1), folder, content, null,
                DocumentOrigin.SOURCE, null, areaId, sourceId);
        DataSource dataSource = dataSource(tenant);
        if (documentId != null) {
            Optional<Document> replaced = documents.replace(dataSource, documentId, document);
            if (replaced.isPresent()) {
                return new Pushed(queued(tenant, replaced.get(), document, content), PushOutcome.REPLACED);
            }
            Optional<Document> unchanged = documents.findById(dataSource, documentId);
            if (unchanged.isPresent()) {
                return new Pushed(unchanged.get(), PushOutcome.UNCHANGED);
            }
        }
        return new Pushed(queued(tenant, documents.insert(dataSource, document), document, content), PushOutcome.CREATED);
    }

    /**
     * 409 when the tenant's documents cannot be embedded (no embedding model, no key for it) - a knowledge source's run
     * asks before it reads anything.
     */
    public void requireEmbedding(Long tenantId) {
        requireEmbedding(tenant(tenantId));
    }

    /** Whether the tenant's image model reads images and scans - a knowledge source reads image files only then. */
    public boolean readsImages(Long tenantId) {
        return hasScanReader(tenant(tenantId));
    }

    /** Deletes a removed knowledge source's documents with their chunks and original files; answers how many. */
    public int deleteBySource(Long tenantId, Long sourceId) {
        Tenant tenant = tenant(tenantId);
        List<Long> deleted = documents.deleteBySource(dataSource(tenant), sourceId);
        removeChunks(tenant, deleted, () -> embeddings.removeDocuments(tenant.databaseName(), deleted));
        for (Long documentId : deleted) {
            deleteOriginal(tenantId, documentId);
        }
        return deleted.size();
    }

    /** A removed knowledge source's documents stay as ordinary uploads, no longer kept up to date. */
    public void detachSource(Long tenantId, Long sourceId) {
        documents.detachSource(dataSource(tenant(tenantId)), sourceId);
    }

    /**
     * A knowledge source writes into another area: its documents still in the old one move along (nothing embedded
     * again); one moved by hand into a third area stays there.
     */
    public void moveSourceDocuments(Long tenantId, Long sourceId, @Nullable Long fromAreaId, @Nullable Long toAreaId) {
        documents.moveSourceDocuments(dataSource(tenant(tenantId)), sourceId, fromAreaId, toAreaId);
    }

    /**
     * Moves a document to another area (null: the company knowledge) - who finds it changes at once, nothing is
     * embedded again. 404 for an unknown document, 400 for an unknown area, 409 when a ZIP archive's file of the same
     * path is in that area already. No transaction: the move is one statement; the tenant database is reached by plain
     * JDBC, which no @Transactional covers.
     */
    public Document moveToArea(Long tenantId, Long documentId, @Nullable Long areaId) {
        Tenant tenant = tenant(tenantId);
        Long area = areas.requireArea(tenantId, areaId);
        DataSource dataSource = dataSource(tenant);
        Document document = documents.findById(dataSource, documentId)
                .orElseThrow(() -> ServiceException.notFound("document.notFound", "Document not found"));
        if (document.origin() == DocumentOrigin.UPLOAD && document.externalId() != null
                && documents.findImported(dataSource, area, document.externalId())
                        .filter(other -> !other.id().equals(documentId)).isPresent()) {
            throw ServiceException.conflict("document.samePathInArea",
                    "A document of the same path is in that area already");
        }
        documents.updateArea(dataSource, documentId, area);
        return documents.findById(dataSource, documentId).orElseThrow();
    }

    /**
     * Embeds a document again - a failed one, or one left waiting when its run broke off (the tenant database out of
     * reach for a moment); it waits (PENDING) from now on, not only once the background job starts.
     */
    public Document retry(Long tenantId, Long documentId) {
        DataSource dataSource = dataSource(tenant(tenantId));
        if (documents.findById(dataSource, documentId).isEmpty()) {
            throw ServiceException.notFound("document.notFound", "Document not found");
        }
        documents.updateStatus(dataSource, documentId, DocumentStatus.PENDING, null, 0);
        indexer.submit(tenantId, documentId);
        return documents.findById(dataSource, documentId).orElseThrow();
    }

    /** Deletes the document and its chunks - the chat no longer finds it. */
    public boolean delete(Long tenantId, Long documentId) {
        Tenant tenant = tenant(tenantId);
        if (!documents.delete(dataSource(tenant), documentId)) {
            return false;
        }
        removeLeftovers(tenant, documentId);
        return true;
    }

    /**
     * Deletes the document if someone with this scope finds it - the client software; false otherwise. The area is
     * checked in the statement that deletes, so a document moved into another area meanwhile stays.
     */
    public boolean delete(Long tenantId, KnowledgeScope scope, Long documentId) {
        if (scope.findsEverything()) {
            return delete(tenantId, documentId);
        }
        Tenant tenant = tenant(tenantId);
        if (!documents.delete(dataSource(tenant), documentId, scope.areaIdList())) {
            return false;
        }
        removeLeftovers(tenant, documentId);
        return true;
    }

    /** The chunks and the original file of a deleted document - the chunks first, they hold the document's text. */
    private void removeLeftovers(Tenant tenant, Long documentId) {
        removeChunks(tenant, List.of(documentId), () -> {
            if (tenant.embeddingModelId() != null) {
                models.findById(tenant.embeddingModelId()).ifPresent(model ->
                        embeddings.removeDocument(tenant.databaseName(), model.dimension(), documentId));
            }
        });
        deleteOriginal(tenant.id(), documentId);
    }

    /**
     * The rows are gone and the search joins them, so chunks left behind are found by no one; they go with the next
     * change of the embedding model or the tenant. A 500 would only stop the removal of the original files.
     */
    private void removeChunks(Tenant tenant, List<Long> documentIds, Runnable removal) {
        try {
            removal.run();
        } catch (RuntimeException e) {
            LOG.warn("Chunks of deleted documents {} of tenant {} could not be removed: {}", documentIds, tenant.id(),
                    e.toString());
        }
    }

    /** The row is gone; an original left behind is unreachable - a 500 would only stop the cleanup of the rest. */
    private void deleteOriginal(Long tenantId, Long documentId) {
        try {
            storage.delete(originalKey(tenantId, documentId));
        } catch (RuntimeException e) {
            LOG.warn("Original of deleted document {} of tenant {} could not be removed: {}", documentId, tenantId,
                    e.toString());
        }
    }

    /**
     * Deletes the document the client software pushed with this externalId; false if there is none, or it is in an
     * area the key does not find.
     */
    public boolean deleteByExternalId(Long tenantId, KnowledgeScope scope, String externalId) {
        String key = externalId(externalId);
        if (key == null) {
            throw ServiceException.badRequest("document.externalIdRequired", "externalId is required");
        }
        return documents.findPushed(dataSource(tenant(tenantId)), key)
                .map(document -> delete(tenantId, scope, document.id()))
                .orElse(false);
    }

    /**
     * Deletes every document uploaded in the frontend into the company knowledge (area "company") or into an area (its
     * id) - before a ZIP archive is imported anew, so files no longer in it do not stay. The client software's documents
     * stay: it keeps them itself. Answers how many were deleted; 400 for anything else as area or an unknown area.
     * No transaction: the rows go in one statement first - from then on the search finds none of their chunks (it joins
     * the document) -, then the chunks and the files; chunks or a file that cannot be removed stay behind unreachable.
     */
    public int deleteUploads(Long tenantId, @Nullable String area) {
        Tenant tenant = tenant(tenantId);
        Long areaId = areas.requireArea(tenantId, uploadArea(area));
        List<Long> deleted = documents.deleteUploads(dataSource(tenant), areaId);
        removeChunks(tenant, deleted, () -> embeddings.removeDocuments(tenant.databaseName(), deleted));
        for (Long documentId : deleted) {
            deleteOriginal(tenantId, documentId);
        }
        return deleted.size();
    }

    /** The area of {@link #deleteUploads}: "company" for the company knowledge (null), else an area's id. */
    private static @Nullable Long uploadArea(@Nullable String area) {
        if (COMPANY_KNOWLEDGE.equals(area)) {
            return null;
        }
        try {
            return Long.valueOf(area == null ? "" : area.strip());
        } catch (NumberFormatException e) {
            throw ServiceException.badRequest("document.areaInvalid", "area must be \"" + COMPANY_KNOWLEDGE
                    + "\" or an area's id");
        }
    }

    /**
     * Sets the model the tenant's documents are embedded with. Changing it drops every chunk (their vector length
     * belongs to the previous model) and embeds every document again - that costs as much as uploading them anew.
     * Waits while a document of the tenant is being embedded. The model is saved last: if dropping the chunks fails
     * (another connection holds the table past lock_timeout), the tenant keeps the previous model with its chunks, and
     * the next attempt does it all again - saved first, that attempt would find the model already set and leave the
     * previous model's chunks to be searched with the new one's vectors.
     */
    public Tenant setEmbeddingModel(Long tenantId, Long modelId) {
        tenant(tenantId);
        AiModel model = tenantModels.requireUsable(tenantId, modelId, ModelKind.EMBEDDING);
        return indexer.withTenantLock(tenantId, () -> {
            Tenant tenant = tenant(tenantId);
            if (Objects.equals(tenant.embeddingModelId(), model.id())) {
                return tenant;
            }
            if (tenant.embeddingModelId() != null) {
                // Without a key every document would end failed, its chunks dropped. A first choice drops nothing:
                // the admin may choose it before the tenant admin enters the key.
                keys.resolve(tenant, model);
            }
            embeddings.drop(tenant.databaseName());
            DataSource dataSource = dataSource(tenant);
            documents.markAllPending(dataSource);
            tenants.updateEmbeddingModelId(tenantId, model.id());
            documents.findUnfinishedIds(dataSource).forEach(documentId -> indexer.submit(tenantId, documentId));
            return tenant(tenantId);
        });
    }

    /**
     * Sets the reranker the tenant's knowledge search reorders its candidates with (docs/architecture/ai-features.md
     * "Reranking"); null switches it off. Nothing is embedded again - it only changes the order of what the search finds.
     * 400 for a model that is not an active reranker assigned to the tenant.
     */
    @Transactional
    public Tenant setRerankingModel(Long tenantId, @Nullable Long modelId) {
        tenant(tenantId);
        Long rerankingModelId = modelId == null ? null
                : tenantModels.requireUsable(tenantId, modelId, ModelKind.RERANK).id();
        tenants.updateRerankingModelId(tenantId, rerankingModelId);
        return tenant(tenantId);
    }

    /**
     * Embeds every document of the tenant again with its model - after a change of how documents are cut into chunks
     * (a chunk's context header), which only a new embedding brings to the documents embedded before. Costs as much as
     * uploading them anew. Like a change of the model: every document waits for its run - a failed one too - and is
     * not found meanwhile, a document that fails can be retried, and a restart takes up what is left; its chunks are
     * replaced, not dropped together. 409 without a model or without a key for it - every document would end failed. Answers how many. No
     * transaction: one statement on the tenant database, reached by plain JDBC, which no @Transactional covers; the
     * lock keeps the indexer out.
     */
    public int embedAllAgain(Long tenantId) {
        tenant(tenantId);
        return indexer.withTenantLock(tenantId, () -> {
            Tenant tenant = tenant(tenantId);
            keys.resolve(tenant, embeddingModel(tenant));
            DataSource dataSource = dataSource(tenant);
            documents.markAllPending(dataSource);
            List<Long> waiting = documents.findUnfinishedIds(dataSource);
            waiting.forEach(documentId -> indexer.submit(tenantId, documentId));
            return waiting.size();
        });
    }

    /**
     * Sets the language the full-text search reads the tenant's documents in. Nothing is embedded again - the chunks'
     * text is stored; only the full-text index is built for the new language, in the background, and until it stands
     * the full-text search reads every chunk (right, slower). One statement and no surrounding transaction: the build
     * must read the language already committed. 400 without a language.
     */
    public Tenant setSearchLanguage(Long tenantId, SearchLanguage language) {
        Tenant tenant = tenant(tenantId);
        if (language == null) {
            throw ServiceException.badRequest("document.searchLanguageMissing", "The search language is missing");
        }
        if (tenant.searchLanguage() == language) {
            return tenant;
        }
        tenants.updateSearchLanguage(tenantId, language);
        indexer.ensureTextIndex(tenantId);
        return tenant(tenantId);
    }

    /**
     * The document to store, with its extracted text. An image, and a PDF with pages of (almost) no text, is read by the
     * image model before it is embedded - if the tenant has one ({@link ScanReader}). 400 for an empty, too large or
     * unreadable document or one without text (e.g. a scan without the image model), 409 for an image without the
     * image model, 415 for a file type that is not allowed.
     */
    private DocumentRepository.NewDocument extract(Tenant tenant, String fileName, String folder, byte[] content,
                                                   Long uploadedBy, DocumentOrigin origin, String externalId,
                                                   @Nullable Long areaId, @Nullable Long sourceId) {
        if (fileName == null || fileName.isBlank()) {
            throw ServiceException.badRequest("document.fileNameMissing", "The file name is missing");
        }
        if (content != null && content.length > MAX_DOCUMENT_BYTES) {
            throw ServiceException.badRequest("document.tooLarge", "A document may have at most "
                    + MAX_DOCUMENT_BYTES / (1024 * 1024) + " MB",
                    Map.of("maxMb", String.valueOf(MAX_DOCUMENT_BYTES / (1024 * 1024))));
        }
        String type = extractor.detect(fileName, content);
        boolean image = DocumentImages.IMAGES.contains(type);
        boolean scanReader = hasScanReader(tenant);
        if (image && !scanReader) {
            throw ServiceException.conflict("document.imageNeedsScanModel",
                    "Images are read by the model reading images and scans - the admin chooses "
                    + "one in the model catalog and assigns it to the tenant");
        }
        String text = image ? "" : extractor.extract(fileName, content).text();
        boolean readsImages = image || (scanReader && ScanReader.needsReading(type, content));
        if (!readsImages && text.isEmpty()) {
            throw ServiceException.badRequest("document.noText", "The document contains no text");
        }
        // What the image model reads is not known yet: the file itself tells an unchanged one.
        String contentHash = readsImages ? sha256(content) : sha256(text.getBytes(StandardCharsets.UTF_8));
        return new DocumentRepository.NewDocument(fileName.strip(), folder, type, content.length, text, readsImages,
                uploadedBy, origin, externalId, contentHash, areaId, sourceId);
    }

    /** Queues the stored document for embedding - with its original file when the image model reads it first. */
    private Document queued(Tenant tenant, Document stored, DocumentRepository.NewDocument document, byte[] content) {
        if (document.readsImages()) {
            storage.put(originalKey(tenant.id(), stored.id()), content, document.contentType());
        } else {
            // A replaced document no longer needs the file of its previous version.
            storage.delete(originalKey(tenant.id(), stored.id()));
        }
        indexer.submit(tenant.id(), stored.id());
        return stored;
    }

    /** Where a document's original file is kept until the image model has read it. */
    static String originalKey(Long tenantId, Long documentId) {
        return TenantService.storagePrefix(tenantId) + "documents/" + documentId;
    }

    private boolean hasScanReader(Tenant tenant) {
        return tenantModels.findScanReader(tenant.id()).flatMap(model -> keys.find(tenant, model)).isPresent();
    }

    /** 409 without an embedding model or without a key for it. */
    private void requireEmbedding(Tenant tenant) {
        keys.resolve(tenant, embeddingModel(tenant));
    }

    /** The trimmed externalId; null when none was sent. */
    private static String externalId(String externalId) {
        if (externalId == null) {
            return null;
        }
        String key = externalId.strip();
        if (key.isEmpty() || key.length() > MAX_EXTERNAL_ID) {
            throw ServiceException.badRequest("document.externalIdLength", "externalId must have 1 to "
                    + MAX_EXTERNAL_ID + " characters",
                    Map.of("max", String.valueOf(MAX_EXTERNAL_ID)));
        }
        return key;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    private AiModel embeddingModel(Tenant tenant) {
        if (tenant.embeddingModelId() == null) {
            throw ServiceException.conflict("document.chooseEmbeddingModel", "Choose an embedding model first");
        }
        return models.findById(tenant.embeddingModelId())
                .orElseThrow(() -> ServiceException.conflict("document.chooseEmbeddingModel",
                        "Choose an embedding model first"));
    }

    private Tenant tenant(Long tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ServiceException.notFound("tenant.notFound",
                "Tenant not found"));
    }

    private DataSource dataSource(Tenant tenant) {
        return databases.dataSource(tenant.databaseName());
    }

    /** What a push did: a new document, an existing one replaced, or nothing (unchanged). */
    public enum PushOutcome {
        CREATED,
        REPLACED,
        UNCHANGED
    }

    public record Pushed(Document document, PushOutcome outcome) {
    }

    /** What importing a file of an archive did. */
    public enum ImportOutcome {
        CREATED,
        REPLACED,
        UNCHANGED,
        /** Its content is in the knowledge base already, under another path - not stored a second time. */
        DUPLICATE,
        /** Not read: a hidden file, a type that is not allowed, no text, too large, ... */
        SKIPPED
    }

    /**
     * A file of an archive: its path, what happened, the document (for DUPLICATE the one with the same content), and
     * for DUPLICATE that document's path, for SKIPPED the reason.
     */
    public record ImportedFile(String path, ImportOutcome outcome, @Nullable Long documentId, @Nullable String detail) {
    }

    public record ArchiveImport(List<ImportedFile> files) {
    }
}
