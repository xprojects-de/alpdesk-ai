package de.alpdesk.services.task.step;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentImages;
import de.alpdesk.services.document.DocumentService;
import de.alpdesk.services.document.DocumentTextExtractor;
import de.alpdesk.services.document.ScanReader;
import de.alpdesk.services.task.StepContext;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A document's content made a run file, for the steps that take documents (read_documents, a call_tool source): the
 * type by content, Tika's text for the agreed types, the tenant's image model for images and scanned pages
 * (docs/architecture/tasks.md "read_documents"). A document that cannot be read is no failure - it is kept with the
 * reason, and with whether the next run tries again.
 */
@Singleton
class DocumentReader {

    private final TaskRunRepository runs;
    private final DocumentTextExtractor extractor;
    private final ScanReader scanReader;
    private final TenantModelService models;
    private final ProviderKeyService keys;

    DocumentReader(TaskRunRepository runs, DocumentTextExtractor extractor, ScanReader scanReader,
                   TenantModelService models, ProviderKeyService keys) {
        this.runs = runs;
        this.extractor = extractor;
        this.scanReader = scanReader;
        this.models = models;
        this.keys = keys;
    }

    /**
     * A document as the next step takes it (its name, its source, the run file of its text or the reason it could not
     * be read), and whether the next run reads it again.
     */
    record Read(Map<String, Object> document, boolean again) {

        static Read refused(String name, String source, String reason, boolean again) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("name", name);
            document.put("source", source);
            document.put("problem", reason);
            return new Read(document, again);
        }
    }

    /** The reading of one step - the image model looked up once, and only when a document needs it. */
    Reading reading(StepContext context) {
        return new Reading(context);
    }

    /** "<name>#<checksum>": the same document gives the same, a changed one another - what a CSV recognises a row by. */
    static String source(String name, byte[] content) {
        return name + "#" + DocumentService.sha256(content).substring(0, 12);
    }

    final class Reading {
        private final StepContext context;
        private boolean resolved;
        private @Nullable AiModel imageModel;

        Reading(StepContext context) {
            this.context = context;
        }

        /**
         * The document's text as a run file named by its name. What the document is to blame for - empty, broken, a
         * type not agreed, no text, too long, an image or PDF that cannot be decoded - is reported once; no image model
         * or no key for it is read again by the next run; anything else (a provider failing) fails the step.
         * <p>
         * A document read again keeps its name as source, without the checksum: its row now is an unclear one, and the
         * clear row of the next run must not look like it - a CSV would skip it as already there.
         *
         * @param name       the document's name in the run (its path below the folder, a tool's file name)
         * @param fileName   the name its type is told apart by, where the content leaves it open
         * @param sourceName what its source begins with - the checksum of the content follows
         */
        Read read(String name, String fileName, String sourceName, byte[] content) {
            String source = source(sourceName, content);
            try {
                String text = text(fileName, content);
                if (text.isBlank()) {
                    return Read.refused(name, source, "The document has no text", false);
                }
                List<RunFile.Line> lines = lines(text);
                if (lines.size() > ReadFilesStep.MAX_LINES) {
                    return Read.refused(name, source, "The document is too long - more than " + ReadFilesStep.MAX_LINES
                            + " lines", false);
                }
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("name", name);
                document.put("source", source);
                document.put("fileId", runs.saveFile(context.dataSource(), context.runId(), context.step().id(), name,
                        lines));
                return new Read(document, false);
            } catch (ServiceException e) {
                if (e.code().equals("document.noScanModel") || e.code().equals("providerKey.missing")) {
                    return Read.refused(name, sourceName, e.getMessage(), true);
                }
                if (isTheDocuments(e.code())) {
                    return Read.refused(name, source, e.getMessage(), false);
                }
                throw e;
            }
        }

        /** A failure the document itself is to blame for - read again it would fail again, on every run. */
        private static boolean isTheDocuments(String code) {
            return code.startsWith("document.") || code.startsWith("image.") || code.startsWith("pdf.");
        }

        /**
         * Tika's text for the agreed types, replaced by the image model's reading where pages are images; an image is the
         * model's reading alone. A type not allowed, or an image without a model to read it, is refused.
         */
        private String text(String fileName, byte[] content) {
            String type = extractor.detect(fileName, content);
            boolean image = DocumentImages.IMAGES.contains(type);
            if (!image && !DocumentTextExtractor.ALLOWED.containsKey(type)) {
                throw ServiceException.badRequest("document.typeNotSupported", "Not a readable document (" + type + ")");
            }
            String text = image ? "" : extractor.text(fileName, type, content);
            if (ScanReader.needsReading(type, content)) {
                if (imageModel() != null) {
                    ResolvedKey key = keys.resolve(context.tenant(), imageModel);
                    return scanReader.read(context.tenant(), imageModel, key, type, fileName, content, context.spent());
                }
                if (text.isBlank()) {
                    throw ServiceException.conflict("document.noScanModel", "No model reads images and scans for this "
                            + "tenant - the admin chooses one in the model catalog");
                }
            }
            return text;
        }

        private @Nullable AiModel imageModel() {
            if (!resolved) {
                resolved = true;
                imageModel = models.findScanReader(context.tenant().id()).orElse(null);
            }
            return imageModel;
        }
    }

    /** Numbered lines, as a run file keeps them - not cut: a document is no log, an amount may end a long line. */
    private static List<RunFile.Line> lines(String text) {
        List<RunFile.Line> lines = new ArrayList<>();
        long number = 0;
        for (String line : text.split("\\r?\\n", -1)) {
            lines.add(new RunFile.Line(++number, line));
        }
        return lines;
    }
}
