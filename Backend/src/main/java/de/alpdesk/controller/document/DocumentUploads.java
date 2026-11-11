package de.alpdesk.controller.document;

import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentService;
import io.micronaut.http.multipart.CompletedFileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.Function;

/**
 * What the document controllers share around uploads. The server takes requests up to the size of a ZIP archive
 * (micronaut.server.* in application.properties), so a single document's limit is checked here, before it is read.
 */
public final class DocumentUploads {

    private DocumentUploads() {
    }

    /** The document's bytes - 400 when it is larger than a document may be. */
    public static byte[] content(CompletedFileUpload upload) {
        if (upload.getSize() > DocumentService.MAX_DOCUMENT_BYTES) {
            throw ServiceException.badRequest("document.tooLarge", "A document may have at most "
                    + DocumentService.MAX_DOCUMENT_BYTES / (1024 * 1024) + " MB",
                    Map.of("maxMb", String.valueOf(DocumentService.MAX_DOCUMENT_BYTES / (1024 * 1024))));
        }
        try {
            return upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copies the uploaded archive into a temporary file of its own and hands it to the import; the file is deleted
     * afterwards, whatever happens. The upload itself is already on disk (micronaut.server.multipart.mixed), not in
     * memory - it is streamed, never read whole.
     */
    public static <T> T withArchive(CompletedFileUpload upload, Function<Path, T> importer) {
        Path zip = null;
        try (InputStream in = upload.getInputStream()) {
            zip = Files.createTempFile("alpdesk-archive-", ".zip");
            Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
            return importer.apply(zip);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(zip);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A temporary file - the system's cleanup takes it.
        }
    }
}
