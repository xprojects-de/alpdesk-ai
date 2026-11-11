package de.alpdesk.services.task.step;

import de.alpdesk.entities.task.ReadPosition;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentService;
import de.alpdesk.services.filestore.FileStoreService;
import de.alpdesk.services.filestore.FileStoreSession;
import de.alpdesk.services.filestore.NamePattern;
import de.alpdesk.services.filestore.RemoteFile;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskDefinition.ReadDocuments;
import de.alpdesk.services.task.TaskResults;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * read_documents (docs/architecture/tasks.md "read_documents"): the documents of a store's folder - with its subfolders
 * on request - whose names match one of the patterns, each read whole and once: Tika for the agreed types, the tenant's
 * image model for images and scanned pages. Each document's text is a run file; one that cannot be read is no failure
 * of the run but a document with the reason, which the next step turns into an unclear row - so it is seen, not lost.
 * <p>
 * A document is known by its path, size and modification time (the read positions, written only after the run
 * succeeded): unchanged, it is not read again; changed, it is read again. At most {@value #MAX_DOCUMENTS} per run, the
 * oldest first - the rest come with the next run; one to be read again does not count (else 50 scans waiting for an
 * image model would hold back every newer document), up to {@value #MAX_READ_AGAIN} of them. What the document itself
 * is to blame for (broken, empty, a type not agreed) is reported once; what may be different next time (the store
 * refusing to read it, no image model or no key for it yet) is reported and read again by the next run; a provider
 * failing fails the run, as in every step. Not in a transaction: a server or the image model may take minutes.
 */
@Singleton
public class ReadDocumentsStep {

    static final int MAX_DOCUMENTS = 50;
    static final int MAX_READ_AGAIN = 500;
    /** The folders walked at most below the step's folder, and the entries listed - as a knowledge source's. */
    static final int MAX_DEPTH = 20;
    static final int MAX_LISTED = 20_000;

    private final FileStoreService stores;
    private final TaskRunRepository runs;
    private final DocumentReader reader;

    public ReadDocumentsStep(FileStoreService stores, TaskRunRepository runs, DocumentReader reader) {
        this.stores = stores;
        this.runs = runs;
        this.reader = reader;
    }

    public StepOutcome run(StepContext context) {
        ReadDocuments settings = context.step().readDocuments();
        Map<String, ReadPosition> known = new HashMap<>();
        for (ReadPosition position : runs.findPositions(context.dataSource(), context.taskId(), settings.storeId())) {
            known.put(position.path(), position);
        }
        String folder = stores.folderOf(context.tenant().id(), settings.storeId(), settings.folder());
        List<Map<String, Object>> documents = new ArrayList<>();
        List<Map<String, Object>> positions = new ArrayList<>();
        boolean truncated;
        try (FileStoreSession session = stores.open(context.tenant().id(), settings.storeId())) {
            Listing listing = new Listing(settings);
            listing.walk(session, folder, "", 0);
            List<Listed> fresh = new ArrayList<>();
            for (Listed file : listing.files) {
                if (changed(file, known.get(file.absolute()))) {
                    fresh.add(file);
                }
            }
            fresh.sort(Comparator.comparing((Listed file) -> file.entry().modified(),
                    Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Listed::relative));
            DocumentReader.Reading reading = reader.reading(context);
            int done = 0;
            int again = 0;
            int looked = 0;
            for (Listed file : fresh) {
                if (done == MAX_DOCUMENTS || again == MAX_READ_AGAIN) {
                    break;
                }
                if (runs.cancelRequested(context.dataSource(), context.runId())) {
                    throw ServiceException.conflict("task.cancelled", "The run was cancelled");
                }
                looked++;
                DocumentReader.Read read = read(session, file, reading);
                documents.add(read.document());
                if (read.again()) {
                    again++;
                } else {
                    done++;
                    positions.add(position(settings.storeId(), file));
                }
            }
            truncated = looked < fresh.size() || !listing.complete;
        }
        List<Long> fileIds = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            if (document.get("fileId") instanceof Long id) {
                fileIds.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.FILES, documents.size());
        result.put(TaskResults.TRUNCATED, truncated);
        result.put(TaskResults.FILE_IDS, fileIds);
        result.put(TaskResults.DOCUMENTS, documents);
        result.put(TaskResults.POSITIONS, positions);
        return StepOutcome.of(result);
    }

    /**
     * New, or not the size and time it had when it was read. The time to the millisecond, as the position keeps it - a
     * store giving finer times would otherwise count every document as changed.
     */
    static boolean changed(Listed file, @Nullable ReadPosition position) {
        if (position == null) {
            return true;
        }
        return position.byteOffset() != file.entry().size()
                || !Objects.equals(millis(position.modifiedAt()), millis(file.entry().modified()));
    }

    private static @Nullable Long millis(@Nullable Instant time) {
        return time == null ? null : time.toEpochMilli();
    }

    /**
     * The file's content read whole and made a run file. What the store refuses (no right to the file, it went away) is
     * read again by the next run, as a knowledge source leaves such a file open; a file too large is reported once.
     */
    private DocumentReader.Read read(FileStoreSession session, Listed file, DocumentReader.Reading reading) {
        if (file.entry().size() > DocumentService.MAX_DOCUMENT_BYTES) {
            return tooLarge(file);
        }
        byte[] content;
        try {
            content = session.readWhole(file.absolute(), file.entry().size(), DocumentService.MAX_DOCUMENT_BYTES);
        } catch (ServiceException e) {
            if (e.code().startsWith("fileStore.")) {
                return DocumentReader.Read.refused(file.relative(), file.relative(), e.getMessage(), true);
            }
            throw e;
        }
        if (content.length > DocumentService.MAX_DOCUMENT_BYTES) {
            return tooLarge(file);
        }
        return reading.read(file.relative(), file.entry().name(), file.relative(), content);
    }

    private static DocumentReader.Read tooLarge(Listed file) {
        return DocumentReader.Read.refused(file.relative(), file.relative(), "The document is larger than "
                + DocumentService.MAX_DOCUMENT_BYTES / 1024 / 1024 + " MB", false);
    }

    /** As a map: the graph's state is plain JSON. The size stands where a log file keeps its offset. */
    private static Map<String, Object> position(Long storeId, Listed file) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("storeId", storeId);
        position.put("path", file.absolute());
        position.put("byteOffset", file.entry().size());
        position.put("lineNumber", 0L);
        position.put("modifiedAt", file.entry().modified() == null ? null : file.entry().modified().toEpochMilli());
        return position;
    }

    /** A file found: its path below the step's folder, its path on the server, and the entry as listed. */
    record Listed(String relative, String absolute, RemoteFile entry) {
    }

    /** The folder - and its subfolders when asked - walked for the files matching a pattern, ignoring case. */
    static final class Listing {
        private final List<String> patterns;
        private final boolean subfolders;
        final List<Listed> files = new ArrayList<>();
        private int listed;
        boolean complete = true;

        Listing(ReadDocuments settings) {
            this.patterns = settings.patterns().stream().map(pattern -> pattern.toLowerCase(Locale.ROOT)).toList();
            this.subfolders = settings.subfolders();
        }

        void walk(FileStoreSession session, String absolute, String relative, int depth) {
            for (RemoteFile entry : session.list(absolute)) {
                if (++listed > MAX_LISTED) {
                    complete = false;
                    return;
                }
                if (RemoteFile.isHidden(entry.name())) {
                    continue;
                }
                String path = relative.isEmpty() ? entry.name() : relative + "/" + entry.name();
                String child = absolute.endsWith("/") ? absolute + entry.name() : absolute + "/" + entry.name();
                if (entry.folder()) {
                    if (subfolders && depth + 1 < MAX_DEPTH) {
                        walk(session, child, path, depth + 1);
                    } else if (subfolders) {
                        complete = false;
                    }
                } else if (matches(entry.name())) {
                    files.add(new Listed(path, child, entry));
                }
            }
        }

        /** "*.pdf" takes INVOICE.PDF too - names come as their senders wrote them. */
        private boolean matches(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return patterns.stream().anyMatch(pattern -> NamePattern.matches(pattern, lower));
        }
    }
}
