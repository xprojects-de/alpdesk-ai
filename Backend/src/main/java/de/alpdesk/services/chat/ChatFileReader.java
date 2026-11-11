package de.alpdesk.services.chat;

import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentImages;
import de.alpdesk.services.document.DocumentTextExtractor;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a file uploaded into a chat into what the model can read: the text of the document types the knowledge base
 * allows (Tika), an image as a JPEG of at most {@link DocumentImages#MAX_EDGE} pixels, a scanned PDF (without a text layer) as one
 * such image per page - all three providers' chat models read images - and audio as the small copy
 * {@link MediaConverter} makes for the models that hear it. A video is taken as it is: the model examines it with code
 * in the tenant's sandbox (docs/architecture/code-execution.md "Videos").
 */
@Singleton
public class ChatFileReader {

    /** The pages of a scanned PDF turned into images at most. */
    static final int MAX_PAGES = 20;
    /** Below this many characters of text per page a PDF counts as scanned. */
    private static final int SCANNED_CHARACTERS_PER_PAGE = 50;
    /**
     * Playlists, refused although Tika may call them audio (an M3U with CRLF line ends is audio/x-mpegurl): ffmpeg would
     * follow one to the network or to the server's own files - other tenants' media.
     */
    private static final Set<String> PLAYLISTS = Set.of("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl",
            "application/vnd.apple.mpegurl", "audio/x-scpls");

    private final DocumentTextExtractor extractor;
    private final MediaConverter media;

    public ChatFileReader(DocumentTextExtractor extractor, MediaConverter media) {
        this.extractor = extractor;
        this.media = media;
    }

    /** 400 for an empty or unreadable file, 415 for a type that is neither a document type nor an image. */
    public ReadFile read(String fileName, byte[] content) {
        String type = extractor.detect(fileName, content);
        if (DocumentImages.IMAGES.contains(type)) {
            return new ReadFile(type, null, List.of(DocumentImages.imageAsJpeg(content)), null);
        }
        if (PLAYLISTS.contains(type)) {
            throw unsupported(type);
        }
        // Any type ffmpeg reads: it refuses what it cannot (400).
        if (type.startsWith("audio/")) {
            return new ReadFile(type, null, List.of(), media.audio(content));
        }
        if (type.startsWith("video/")) {
            return new ReadFile(type, null, List.of(), null);
        }
        if (!DocumentTextExtractor.ALLOWED.containsKey(type)) {
            throw unsupported(type);
        }
        String text = extractor.text(fileName, type, content);
        if (type.equals("application/pdf")) {
            List<byte[]> pages = scannedPages(content, text);
            if (!pages.isEmpty()) {
                return new ReadFile(type, null, pages, null);
            }
        }
        if (text.isEmpty()) {
            throw ServiceException.badRequest("chatFile.noText", "The file contains no text");
        }
        return new ReadFile(type, text, List.of(), null);
    }

    /** The type Tika recognises in these bytes - for a video above the file limit, its first bytes. */
    public String detect(String fileName, byte[] head) {
        return extractor.detect(fileName, head);
    }

    /**
     * Pages of a PDF as images for the model (1-based, inclusive) - for the tool read_file, which may look at any PDF's
     * pages, not only a scan's. Pages beyond the last are left out. 400 if the PDF cannot be read.
     */
    public DocumentImages.RenderedPages renderPages(byte[] pdf, int firstPage, int lastPage) {
        return DocumentImages.renderPages(pdf, firstPage, lastPage);
    }

    /** The pages of a PDF with (almost) no text as images - empty for a PDF with a text layer. */
    private static ServiceException unsupported(String type) {
        return new ServiceException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "chatFile.typeNotSupported",
                "This file type is not supported (" + type + ") - allowed: PDF, DOCX, ODT, RTF, TXT, Markdown, HTML, PPTX, "
                        + "XLSX, ODS, CSV, PNG, JPEG, GIF, BMP, audio, video", Map.of("type", type));
    }

    private static List<byte[]> scannedPages(byte[] content, String text) {
        try (PDDocument document = Loader.loadPDF(content)) {
            int pages = document.getNumberOfPages();
            if (pages == 0 || text.length() >= pages * SCANNED_CHARACTERS_PER_PAGE) {
                return List.of();
            }
            if (pages > MAX_PAGES) {
                throw ServiceException.badRequest("chatFile.scanTooManyPages", "A scanned PDF may have at most "
                        + MAX_PAGES + " pages",
                        Map.of("max", String.valueOf(MAX_PAGES)));
            }
        } catch (IOException e) {
            throw ServiceException.badRequest("pdf.unreadable", "The PDF could not be read");
        }
        return DocumentImages.renderPages(content, 1, MAX_PAGES).images();
    }

    /**
     * The detected type, the text the model reads (null when it reads images or media), the images it is sent, and the
     * model's copy of audio (MP3), else null.
     */
    public record ReadFile(String contentType, String text, List<byte[]> images, byte[] media) {
    }
}
