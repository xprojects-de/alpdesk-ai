package de.alpdesk.services.document;

import de.alpdesk.entities.document.KnowledgeSource;
import de.alpdesk.entities.document.SourceFile;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.document.KnowledgeSourceRepository;
import de.alpdesk.repositories.document.SourceFileRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.filestore.FileStoreService;
import de.alpdesk.services.filestore.FileStoreSession;
import de.alpdesk.services.filestore.NamePattern;
import de.alpdesk.services.filestore.RemoteFile;
import de.alpdesk.services.tenant.TenantDatabases;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * A knowledge source's run (docs/architecture/knowledge-sources.md "A run"), on the executor "sources": lists the
 * folder's tree, compares it with what the source saw (source_file), reads new and changed files into the knowledge
 * through {@link DocumentService#fromSource}, deletes the documents of vanished files - only after a complete listing -
 * and records the outcome. Nothing to resume after a restart: every file is recorded when it is done.
 */
@Singleton
public class SourceSync {

    private static final Logger LOG = LoggerFactory.getLogger(SourceSync.class);
    /** What a scheduled run reads at most - a flood nobody started on purpose is read over several runs. */
    static final int MAX_FILES_PER_RUN = 100;
    static final long MAX_BYTES_PER_RUN = 250L * 1024 * 1024;
    /** What a listing walks at most - every entry, folders and names the pattern passes over too; beyond it nothing
     * is deleted. */
    static final int MAX_LISTED = 20_000;
    static final int MAX_DEPTH = 20;
    /** What is read at a time of a file that grew since it was listed. */

    private final KnowledgeSourceRepository sources;
    private final SourceFileRepository files;
    private final FileStoreService stores;
    private final DocumentService documents;
    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final ExecutorService executor;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    /** One installation, no cluster: a cancel is a flag in memory, looked at between files. */
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    public SourceSync(KnowledgeSourceRepository sources, SourceFileRepository files, FileStoreService stores,
                      DocumentService documents, TenantRepository tenants, TenantDatabases databases,
                      @Named("sources") ExecutorService executor) {
        this.sources = sources;
        this.files = files;
        this.stores = stores;
        this.documents = documents;
        this.tenants = tenants;
        this.databases = databases;
        this.executor = executor;
    }

    /** How a run reads: LISTING reads nothing, BY_HAND everything open, SCHEDULED at most the limits. */
    public enum Kind {
        LISTING,
        BY_HAND,
        SCHEDULED
    }

    /** Starts a run in the background; false while the source runs already - one run per source at a time. */
    public boolean start(Long tenantId, Long sourceId, Kind kind) {
        if (!running.add(sourceId)) {
            return false;
        }
        // A cancel that saw the last run still going may have landed just after it ended - it is not meant for this one
        cancelled.remove(sourceId);
        try {
            executor.execute(() -> runLogged(tenantId, sourceId, kind));
        } catch (RejectedExecutionException e) {
            running.remove(sourceId);
            throw e;
        }
        return true;
    }

    public boolean isRunning(Long sourceId) {
        return running.contains(sourceId);
    }

    /** The sources running now - a copy, taken before a list of them is read. */
    public Set<Long> running() {
        return Set.copyOf(running);
    }

    /**
     * Does the work while no run of the source can start - a run starting during a removal would write documents of a
     * source that is gone, one starting while its area changes would file them under the old area. False, and nothing
     * done, while a run is under way.
     */
    public boolean holding(Long sourceId, Runnable work) {
        if (!running.add(sourceId)) {
            return false;
        }
        try {
            work.run();
        } finally {
            cancelled.remove(sourceId);
            running.remove(sourceId);
        }
        return true;
    }

    /** The run stops after the file it is reading; what is read stays, the rest stays open. */
    public void cancel(Long sourceId) {
        if (running.contains(sourceId)) {
            cancelled.add(sourceId);
        }
    }

    private void runLogged(Long tenantId, Long sourceId, Kind kind) {
        try {
            run(tenantId, sourceId, kind);
        } catch (RuntimeException e) {
            // Beyond the store's and a file's failures (the tenant database out of reach): logged here, or it reached
            // only the executor's Future.
            LOG.error("Run of knowledge source {} of tenant {} failed", sourceId, tenantId, e);
            sources.recordRun(sourceId, Instant.now(), "source.failed", 0, 0, 0, 0, 0, null, null, null);
        } finally {
            cancelled.remove(sourceId);
            running.remove(sourceId);
        }
    }

    /** One run, on the caller's thread (the executor's; the specs call it directly). */
    void run(Long tenantId, Long sourceId, Kind kind) {
        Optional<KnowledgeSource> found = sources.findByIdAndTenantId(sourceId, tenantId);
        Optional<Tenant> tenant = tenants.findById(tenantId);
        if (found.isEmpty() || tenant.isEmpty()) {
            return;
        }
        KnowledgeSource source = found.get();
        sources.recordStart(sourceId, Instant.now());
        Outcome outcome = new Outcome();
        try {
            documents.requireEmbedding(tenantId);
            DataSource dataSource = databases.dataSource(tenant.get().databaseName());
            String root = stores.folderOf(tenantId, source.fileStoreId(), source.folder());
            try (FileStoreSession session = stores.open(tenantId, source.fileStoreId())) {
                Listing listing = new Listing(documents.readsImages(tenantId), source.pattern(),
                        () -> cancelled.contains(sourceId));
                listing.walk(session, root, "", 0);
                outcome.skipped = listing.skipped;
                Map<String, SourceFile> known = files.findBySource(dataSource, sourceId);
                outcome.open = open(listing, known);
                outcome.listed = true;
                if (kind != Kind.LISTING) {
                    new Reading(tenantId, source, dataSource, session, known, outcome).read(kind);
                    if (listing.complete && !cancelled.contains(sourceId)) {
                        deleteVanished(tenantId, sourceId, dataSource, listing, known, outcome);
                    }
                }
                outcome.error = cancelled.contains(sourceId) ? "source.cancelled"
                        : !listing.complete ? "source.tooManyFiles" : outcome.unreadable;
                outcome.succeeded = listing.complete;
            }
        } catch (ServiceException e) {
            // The store out of reach, a changed fingerprint, no embedding model: nothing deleted, tried again next time.
            outcome.error = e.code();
            LOG.warn("Run of knowledge source {} of tenant {} failed: {}", sourceId, tenantId, e.code());
        }
        record(sourceId, outcome);
    }

    /**
     * The new files and those whose size or time changed, by path - a file seen as it is now is not read again. One
     * refused for want of an image model is open again once the tenant has one. One the store could not read last time
     * is tried again, after all the others.
     */
    private static List<Listed> open(Listing listing, Map<String, SourceFile> known) {
        List<Listed> open = new ArrayList<>();
        List<Listed> unreadable = new ArrayList<>();
        for (Listed file : listing.files.values()) {
            SourceFile seen = known.get(file.path());
            if (seen != null && seen.unreadable()) {
                unreadable.add(file);
            } else if (seen == null || !seen.unchanged(file.entry().size(), file.entry().modified())
                    || listing.imagesRead && seen.refusedWithoutImageModel()) {
                open.add(file);
            }
        }
        open.addAll(unreadable);
        return open;
    }

    /**
     * The documents of files no longer there - a file the pattern no longer matches included; one only left out by its
     * extension (images while the tenant has no image model) is still there and keeps its document.
     */
    private void deleteVanished(Long tenantId, Long sourceId, DataSource dataSource, Listing listing,
                                Map<String, SourceFile> known, Outcome outcome) {
        for (SourceFile file : known.values()) {
            if (listing.present.contains(file.path())) {
                continue;
            }
            if (file.documentId() != null && documents.delete(tenantId, file.documentId())) {
                outcome.deleted++;
            }
            files.delete(dataSource, sourceId, file.path());
        }
    }

    private void record(Long sourceId, Outcome outcome) {
        Integer openFiles = null;
        Long openBytes = null;
        if (outcome.listed) {
            openFiles = 0;
            openBytes = 0L;
            List<Listed> stillOpen = new ArrayList<>(outcome.unread);
            stillOpen.addAll(outcome.open.subList(outcome.done, outcome.open.size()));
            for (Listed file : stillOpen) {
                openFiles++;
                openBytes += file.entry().size();
            }
        }
        sources.recordRun(sourceId, Instant.now(), outcome.error, outcome.added, outcome.changed, outcome.deleted,
                outcome.refused, outcome.skipped, outcome.succeeded ? Instant.now() : null, openFiles, openBytes);
    }

    /** A file of the listing: its path relative to the source's folder, its path on the server, what the store said. */
    private record Listed(String path, String absolute, RemoteFile entry) {
    }

    /** What a run did - recorded with the source. */
    private static final class Outcome {
        @Nullable String error;
        boolean listed;
        boolean succeeded;
        int added;
        int changed;
        int deleted;
        int refused;
        int skipped;
        List<Listed> open = List.of();
        /** How many of the open files were read or refused. */
        int done;
        /** The files the store could not read - still open - and its code, shown with the source. */
        final List<Listed> unread = new ArrayList<>();
        @Nullable String unreadable;
    }

    /** The folder's tree, walked: the files to read by path, and every file there is (for what vanished). */
    private static final class Listing {
        private final boolean imagesRead;
        private final @Nullable String pattern;
        private final BooleanSupplier cancelled;
        /** Sorted by path - read in this order. */
        final TreeMap<String, Listed> files = new TreeMap<>();
        /** Every file there is and the pattern matches, whatever its type. */
        final Set<String> present = new HashSet<>();
        int skipped;
        /** Every entry walked - a tree of folders or of names the pattern passes over ends the walk as well. */
        int walked;
        boolean complete = true;

        Listing(boolean imagesRead, @Nullable String pattern, BooleanSupplier cancelled) {
            this.imagesRead = imagesRead;
            this.pattern = pattern;
            this.cancelled = cancelled;
        }

        void walk(FileStoreSession session, String absolute, String relative, int depth) {
            for (RemoteFile entry : session.list(absolute)) {
                if (walked >= MAX_LISTED || cancelled.getAsBoolean()) {
                    complete = false;
                    return;
                }
                if (RemoteFile.isHidden(entry.name())) {
                    continue;
                }
                walked++;
                String path = relative.isEmpty() ? entry.name() : relative + "/" + entry.name();
                String child = absolute.endsWith("/") ? absolute + entry.name() : absolute + "/" + entry.name();
                if (entry.folder()) {
                    if (depth + 1 >= MAX_DEPTH) {
                        complete = false;
                    } else {
                        walk(session, child, path, depth + 1);
                    }
                } else if (pattern == null || NamePattern.matches(pattern, entry.name())) {
                    present.add(path);
                    if (DocumentTextExtractor.readableByName(entry.name(), imagesRead)) {
                        files.put(path, new Listed(path, child, entry));
                    } else {
                        skipped++;
                    }
                }
            }
        }
    }

    /** Reads the open files into the knowledge, in the order of their paths. */
    private final class Reading {
        private final Long tenantId;
        private final KnowledgeSource source;
        private final DataSource dataSource;
        private final FileStoreSession session;
        private final Map<String, SourceFile> known;
        private final Outcome outcome;

        Reading(Long tenantId, KnowledgeSource source, DataSource dataSource, FileStoreSession session,
                Map<String, SourceFile> known, Outcome outcome) {
            this.tenantId = tenantId;
            this.source = source;
            this.dataSource = dataSource;
            this.session = session;
            this.known = known;
            this.outcome = outcome;
        }

        void read(Kind kind) {
            int read = 0;
            long bytes = 0;
            for (Listed file : outcome.open) {
                if (cancelled.contains(source.id())) {
                    return;
                }
                boolean refusedUnread = refusedUnread(file);
                if (!refusedUnread) {
                    boolean full = read >= MAX_FILES_PER_RUN || bytes + file.entry().size() > MAX_BYTES_PER_RUN;
                    if (kind == Kind.SCHEDULED && full) {
                        return;
                    }
                    read++;
                    bytes += file.entry().size();
                    readOne(file);
                }
                outcome.done++;
            }
        }

        /** A path too long or a file too large is refused from the listing, without reading it. */
        private boolean refusedUnread(Listed file) {
            if (file.path().length() > DocumentArchive.MAX_PATH) {
                refuse(file, "source.pathTooLong");
                return true;
            }
            if (file.entry().size() > DocumentService.MAX_DOCUMENT_BYTES) {
                refuse(file, "document.tooLarge");
                return true;
            }
            return false;
        }

        /**
         * A file the store cannot read (no right to it, locked) is left open for the next run and the others are read -
         * one such file must not stop the source for good. It is recorded with the store's code, keeping the document of
         * its earlier version, so the next run tries it after the others. A file the knowledge refuses is recorded.
         */
        private void readOne(Listed file) {
            byte[] content;
            try {
                content = session.readWhole(file.absolute(), file.entry().size(), DocumentService.MAX_DOCUMENT_BYTES);
            } catch (ServiceException e) {
                files.save(dataSource, sourceFile(file, documentId(file), e.code()));
                outcome.unread.add(file);
                outcome.unreadable = e.code();
                return;
            }
            try {
                DocumentService.Pushed pushed = documents.fromSource(tenantId, source.id(), source.areaId(), file.path(),
                        documentId(file), content);
                files.save(dataSource, sourceFile(file, pushed.document().id(), null));
                switch (pushed.outcome()) {
                    case CREATED -> outcome.added++;
                    case REPLACED -> outcome.changed++;
                    case UNCHANGED -> { }
                }
            } catch (ServiceException e) {
                refuse(file, e.code());
            }
        }

        /** A refused file keeps the document of its earlier version - it is still found until the file is readable. */
        private void refuse(Listed file, String code) {
            files.save(dataSource, sourceFile(file, documentId(file), code));
            outcome.refused++;
        }

        private @Nullable Long documentId(Listed file) {
            SourceFile seen = known.get(file.path());
            return seen == null ? null : seen.documentId();
        }

        private SourceFile sourceFile(Listed file, @Nullable Long documentId, @Nullable String error) {
            return new SourceFile(source.id(), file.path(), file.entry().size(), file.entry().modified(), documentId,
                    error);
        }
    }
}
