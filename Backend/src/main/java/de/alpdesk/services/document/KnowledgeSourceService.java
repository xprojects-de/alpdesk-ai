package de.alpdesk.services.document;

import de.alpdesk.dto.document.KnowledgeSourceRequest;
import de.alpdesk.dto.document.KnowledgeSourceResponse;
import de.alpdesk.dto.document.SourceFileResponse;
import de.alpdesk.entities.document.KnowledgeSource;
import de.alpdesk.entities.document.SourceFile;
import de.alpdesk.entities.document.SourceSchedule;
import de.alpdesk.entities.filestore.FileStore;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.document.KnowledgeSourceRepository;
import de.alpdesk.repositories.document.SourceFileRepository;
import de.alpdesk.repositories.filestore.FileStoreRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.filestore.FileStoreService;
import de.alpdesk.services.tenant.TenantDatabases;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The tenant's knowledge sources (docs/architecture/knowledge-sources.md), kept by its tenant admins and the admin: a
 * folder of one of its file stores, synced into the company knowledge or an area by {@link SourceSync}. A save lists
 * the folder at once (a listing run, nothing read), so the source shows what is open before anything costs.
 */
@Singleton
public class KnowledgeSourceService {

    public static final int MAX_NAME = 80;
    private static final int MAX_FOLDER = 1000;
    private static final int MAX_PATTERN = 200;
    /** The files without a document a source shows - refused or deleted by hand. */
    static final int FILES_SHOWN = 1000;

    private final KnowledgeSourceRepository sources;
    private final SourceFileRepository files;
    private final FileStoreRepository stores;
    private final FileStoreService storeService;
    private final KnowledgeAreaService areas;
    private final DocumentService documents;
    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final SourceSync sync;
    private final TransactionOperations<Connection> transactions;
    private final ZoneId zone;

    public KnowledgeSourceService(KnowledgeSourceRepository sources, SourceFileRepository files,
                                  FileStoreRepository stores, FileStoreService storeService, KnowledgeAreaService areas,
                                  DocumentService documents, TenantRepository tenants, TenantDatabases databases,
                                  SourceSync sync, TransactionOperations<Connection> transactions,
                                  @Value("${app.timezone}") String zone) {
        this.sources = sources;
        this.files = files;
        this.stores = stores;
        this.storeService = storeService;
        this.areas = areas;
        this.documents = documents;
        this.tenants = tenants;
        this.databases = databases;
        this.sync = sync;
        this.transactions = transactions;
        this.zone = ZoneId.of(zone);
    }

    /** The tenant's sources with their last run; 404 for an unknown tenant. */
    public List<KnowledgeSourceResponse> list(Long tenantId) {
        tenant(tenantId);
        Names names = names(tenantId);
        Set<Long> running = sync.running();
        List<KnowledgeSourceResponse> responses = new ArrayList<>();
        for (KnowledgeSource source : sources.findByTenantIdOrderByName(tenantId)) {
            responses.add(response(source, names, running.contains(source.id())));
        }
        return responses;
    }

    /**
     * Saves the source and lists its folder in the background - nothing is read until a run by hand or the schedule.
     * 400 for invalid input, an unknown store or area; 409 for a name the tenant has, a store not ready (no confirmed
     * fingerprint).
     */
    public KnowledgeSourceResponse create(Long tenantId, KnowledgeSourceRequest request) {
        tenant(tenantId);
        Settings settings = settings(tenantId, request, null);
        boolean active = request.active() == null || request.active();
        Instant now = Instant.now();
        KnowledgeSource saved = transactions.executeWrite(_ -> sources.save(new KnowledgeSource(null, tenantId,
                settings.name(), settings.storeId(), settings.folder(), settings.areaId(), settings.pattern(),
                settings.schedule(), active, nextRun(settings.schedule(), active, now), null, null, null, 0, 0, 0, 0, 0,
                null, 0, 0, now, now)));
        sync.start(tenantId, saved.id(), SourceSync.Kind.LISTING);
        return response(saved, names(tenantId), true);
    }

    /**
     * Changes the source. Another store or folder means other files: its documents are deleted and it is listed anew,
     * as a new source. Another area moves its documents still in the old one (nothing embedded again); another pattern
     * is listed anew - the next reading run deletes what no longer matches. 409 while a run is under way and one of
     * these changes, and no run starts while they are made - one starting between the move and the saved settings
     * would file new documents under the old area for good; a name, the schedule or the switch may change any time.
     */
    public Optional<KnowledgeSourceResponse> update(Long tenantId, Long sourceId, KnowledgeSourceRequest request) {
        Optional<KnowledgeSource> found = sources.findByIdAndTenantId(sourceId, tenantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        KnowledgeSource before = found.get();
        Settings after = settings(tenantId, request, sourceId);
        boolean otherFiles = !before.fileStoreId().equals(after.storeId()) || !before.folder().equals(after.folder());
        boolean otherArea = !Objects.equals(before.areaId(), after.areaId());
        boolean otherPattern = !Objects.equals(before.pattern(), after.pattern());
        boolean active = request.active() == null ? before.active() : request.active();
        Instant nextRunAt = after.schedule() == before.schedule() && active == before.active() ? before.nextRunAt()
                : nextRun(after.schedule(), active, Instant.now());
        Runnable change = () -> {
            if (otherFiles) {
                documents.deleteBySource(tenantId, sourceId);
                files.deleteBySource(dataSource(tenantId), sourceId);
            } else if (otherArea) {
                documents.moveSourceDocuments(tenantId, sourceId, before.areaId(), after.areaId());
            }
            transactions.executeWrite(_ -> {
                sources.updateSettings(sourceId, after.name(), after.storeId(), after.folder(), after.areaId(),
                        after.pattern(), after.schedule().name(), active, nextRunAt, Instant.now());
                return null;
            });
        };
        if (!(otherFiles || otherArea || otherPattern)) {
            change.run();
        } else if (!sync.holding(sourceId, change)) {
            throw running();
        }
        if (otherFiles || otherPattern) {
            sync.start(tenantId, sourceId, SourceSync.Kind.LISTING);
        }
        boolean running = sync.isRunning(sourceId);
        return sources.findById(sourceId).map(source -> response(source, names(tenantId), running));
    }

    /**
     * Removes the source - with its documents, or keeping them as ordinary documents that are no longer kept up to date
     * (keepDocuments). The tenant database first: a failure in between leaves a source to remove again, not documents
     * nobody owns. 409 while a run is under way - it is cancelled first; none starts while it is removed.
     */
    public boolean delete(Long tenantId, Long sourceId, boolean keepDocuments) {
        if (sources.findByIdAndTenantId(sourceId, tenantId).isEmpty()) {
            return false;
        }
        if (!sync.holding(sourceId, () -> remove(tenantId, sourceId, keepDocuments))) {
            throw running();
        }
        return true;
    }

    private void remove(Long tenantId, Long sourceId, boolean keepDocuments) {
        if (keepDocuments) {
            documents.detachSource(tenantId, sourceId);
        } else {
            documents.deleteBySource(tenantId, sourceId);
        }
        files.deleteBySource(dataSource(tenantId), sourceId);
        transactions.executeWrite(_ -> sources.deleteByIdAndTenantId(sourceId, tenantId));
    }

    /** Starts a run by hand - it reads everything open, without the limits of a scheduled run. 409 while one runs. */
    public Optional<KnowledgeSourceResponse> run(Long tenantId, Long sourceId) {
        Optional<KnowledgeSource> found = sources.findByIdAndTenantId(sourceId, tenantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!sync.start(tenantId, sourceId, SourceSync.Kind.BY_HAND)) {
            throw running();
        }
        return Optional.of(response(found.get(), names(tenantId), true));
    }

    /** Stops a run after the file it is reading; nothing to do when none runs. False for an unknown source. */
    public boolean cancel(Long tenantId, Long sourceId) {
        if (sources.findByIdAndTenantId(sourceId, tenantId).isEmpty()) {
            return false;
        }
        sync.cancel(sourceId);
        return true;
    }

    /** The source's files without a document - refused (with the reason) or deleted by hand - at most FILES_SHOWN. */
    public Optional<List<SourceFileResponse>> filesWithoutDocument(Long tenantId, Long sourceId) {
        if (sources.findByIdAndTenantId(sourceId, tenantId).isEmpty()) {
            return Optional.empty();
        }
        List<SourceFileResponse> responses = new ArrayList<>();
        for (SourceFile file : files.findWithoutDocument(dataSource(tenantId), sourceId, FILES_SHOWN)) {
            responses.add(new SourceFileResponse(file.path(), file.size(), file.modified(), file.error()));
        }
        return Optional.of(responses);
    }

    private @Nullable Instant nextRun(SourceSchedule schedule, boolean active, Instant now) {
        return active ? schedule.nextAfter(now, zone) : null;
    }

    private Settings settings(Long tenantId, KnowledgeSourceRequest request, @Nullable Long sourceId) {
        if (request == null) {
            throw ServiceException.badRequest("source.nameRequired", "The name is required");
        }
        String name = name(request.name());
        requireFreeName(tenantId, name, sourceId);
        FileStore store = store(tenantId, request.fileStoreId());
        String folder = folder(request.folder(), storeService.basePathOf(store));
        Long areaId = areas.requireArea(tenantId, request.areaId());
        return new Settings(name, store.id(), folder, areaId, pattern(request.pattern()), schedule(request.schedule()));
    }

    private static String name(@Nullable String name) {
        if (name == null || name.isBlank()) {
            throw ServiceException.badRequest("source.nameRequired", "The name is required");
        }
        String stripped = name.strip();
        if (stripped.length() > MAX_NAME) {
            throw ServiceException.badRequest("source.nameTooLong", "The name may have at most " + MAX_NAME
                    + " characters", Map.of("max", String.valueOf(MAX_NAME)));
        }
        return stripped;
    }

    private void requireFreeName(Long tenantId, String name, @Nullable Long sourceId) {
        boolean taken = sources.findByTenantIdOrderByName(tenantId).stream()
                .anyMatch(other -> other.name().equalsIgnoreCase(name) && !other.id().equals(sourceId));
        if (taken) {
            throw ServiceException.conflict("source.nameTaken", "The tenant has a knowledge source of this name",
                    Map.of("name", name));
        }
    }

    /** The tenant's store - 400 for another tenant's or an unknown one, 409 for one whose server is not confirmed. */
    private FileStore store(Long tenantId, @Nullable Long storeId) {
        FileStore store = (storeId == null ? Optional.<FileStore>empty() : stores.findByIdAndTenantId(storeId, tenantId))
                .orElseThrow(() -> ServiceException.badRequest("source.storeNotFound", "Unknown file store"));
        if (!storeService.readable(store)) {
            throw ServiceException.conflict("source.storeNotReady", "The file store " + store.name() + " is not ready "
                    + "- check it and confirm its server first", Map.of("store", store.name()));
        }
        return store;
    }

    /** Relative to the store's folder, without leading or trailing slashes; "" for the store's folder itself. */
    static String folder(@Nullable String folder, String basePath) {
        if (folder == null || folder.isBlank()) {
            return "";
        }
        String relative = folder.strip().replaceAll("/+", "/");
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        while (relative.endsWith("/")) {
            relative = relative.substring(0, relative.length() - 1);
        }
        boolean invalid = relative.length() > MAX_FOLDER || relative.chars().anyMatch(Character::isISOControl)
                || FileStoreService.folderBelow(basePath, relative) == null;
        for (String segment : relative.split("/")) {
            invalid |= segment.equals(".");
        }
        if (invalid) {
            throw ServiceException.badRequest("source.folderInvalid", "The folder is a path below the store's folder, "
                    + "like Projekte/2025 - without . or ..");
        }
        return relative;
    }

    private static @Nullable String pattern(@Nullable String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String stripped = pattern.strip();
        if (stripped.length() > MAX_PATTERN || stripped.contains("/") || stripped.contains("\\")
                || stripped.chars().anyMatch(Character::isISOControl)) {
            throw ServiceException.badRequest("source.patternInvalid", "The pattern is a file name with * and ?, "
                    + "like *.pdf");
        }
        return stripped;
    }

    private static SourceSchedule schedule(@Nullable String schedule) {
        if (schedule == null || schedule.isBlank()) {
            return SourceSchedule.DAILY;
        }
        try {
            return SourceSchedule.valueOf(schedule.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ServiceException.badRequest("source.scheduleUnknown", "The schedule is MANUAL, HOURLY, "
                    + "EVERY_3_HOURS, EVERY_6_HOURS, EVERY_12_HOURS or DAILY");
        }
    }

    private static ServiceException running() {
        return ServiceException.conflict("source.running", "The knowledge source runs right now - wait for its end or "
                + "cancel it");
    }

    /**
     * running is asked before the source is read (or true for a run just started): a run ending in between then shows
     * as still running and the next look finds its outcome - never as ended with the outcome of the run before.
     */
    private KnowledgeSourceResponse response(KnowledgeSource source, Names names, boolean running) {
        return new KnowledgeSourceResponse(source.id(), source.name(), source.fileStoreId(),
                names.stores().get(source.fileStoreId()), source.folder(), source.areaId(),
                source.areaId() == null ? null : names.areas().get(source.areaId()), source.pattern(),
                source.schedule().name(), source.active(), source.nextRunAt(), running,
                source.lastStartedAt(), source.lastFinishedAt(), source.lastError(), source.lastAdded(),
                source.lastChanged(), source.lastDeleted(), source.lastRefused(), source.lastSkipped(),
                source.lastSucceededAt(), source.openFiles(), source.openBytes());
    }

    private Names names(Long tenantId) {
        Map<Long, String> storeNames = new HashMap<>();
        for (FileStore store : stores.findByTenantIdOrderByName(tenantId)) {
            storeNames.put(store.id(), store.name());
        }
        return new Names(storeNames, areas.namesById(tenantId));
    }

    private DataSource dataSource(Long tenantId) {
        return databases.dataSource(tenant(tenantId).databaseName());
    }

    private Tenant tenant(Long tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ServiceException.notFound("tenant.notFound",
                "Tenant not found"));
    }

    private record Settings(String name, Long storeId, String folder, @Nullable Long areaId, @Nullable String pattern,
                            SourceSchedule schedule) {
    }

    private record Names(Map<Long, String> stores, Map<Long, String> areas) {
    }
}
