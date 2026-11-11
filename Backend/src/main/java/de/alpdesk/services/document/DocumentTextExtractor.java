package de.alpdesk.services.document;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.config.ServiceLoader;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.DefaultParser;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.markdown.MarkdownParser;
import org.apache.tika.parser.pkg.RarParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts the plain text of an uploaded document with Apache Tika - internal only, the first step of embedding it.
 * Only the file types of {@link #ALLOWED} are read, recognised by their content (Tika's detection), not by the file
 * name's extension: images and scans have no text without OCR, archives could be zip bombs, the old binary Office
 * formats give weaker text (decided 6 October 2026).
 */
@Singleton
public class DocumentTextExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentTextExtractor.class);
    /**
     * The most text a document may have: a spreadsheet export of a few megabytes unpacks into hundreds of megabytes of
     * text, held several times in memory and embedded chunk by chunk. 20 million characters are some 8,000 pages.
     */
    public static final int MAX_TEXT_CHARACTERS = 20_000_000;

    /** The most one part of an Office file may unpack to - {@link #requireModestParts}. */
    static final long MAX_PART_BYTES = 200L * 1024 * 1024;

    /**
     * The allowed types - the one place they are named: Tika's name, how the error message calls it, and the file name
     * extensions a knowledge source reads (it skips other files before reading them; upload and ZIP import decide by
     * content alone). A new type added here holds for all of them.
     */
    public static final List<AllowedType> TYPES = List.of(
            new AllowedType("application/pdf", "PDF", "pdf"),
            new AllowedType("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "DOCX", "docx"),
            new AllowedType("application/vnd.oasis.opendocument.text", "ODT", "odt"),
            new AllowedType("application/rtf", "RTF", "rtf"),
            new AllowedType("text/plain", "TXT", "txt", "text"),
            // Tika names a .log file text/x-log - plain text all the same (decided 27 October 2026: logs are read).
            new AllowedType("text/x-log", "TXT", "log"),
            new AllowedType("text/markdown", "Markdown", "md", "markdown"),
            new AllowedType("text/html", "HTML", "html", "htm"),
            new AllowedType("application/xhtml+xml", "HTML", "xhtml"),
            new AllowedType("application/vnd.openxmlformats-officedocument.presentationml.presentation", "PPTX", "pptx"),
            new AllowedType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "XLSX", "xlsx"),
            new AllowedType("application/vnd.oasis.opendocument.spreadsheet", "ODS", "ods"),
            new AllowedType("text/csv", "CSV", "csv"));
    /** The allowed types by Tika's name. */
    public static final Map<String, AllowedType> ALLOWED = byMediaType();
    static final String ALLOWED_NAMES = String.join(", ", TYPES.stream().map(AllowedType::name).distinct().toList());
    private static final Set<String> EXTENSIONS = extensions();

    private final Detector detector = new DefaultDetector();
    private final MimeTypes byName = MimeTypes.getDefaultMimeTypes();
    private final MediaTypeRegistry registry = MediaTypeRegistry.getDefaultRegistry();
    /**
     * One parser for every document - Tika's parsers are thread-safe, and building them loads every parser module.
     * Without RarParser: junrar is excluded from the build (THIRD-PARTY.md), and Tika would log a failed load with its
     * stack trace for every parser built. Without MarkdownParser (new in Tika 4): it renders Markdown as XHTML, whose
     * plain text loses the headings' marks and the blank lines between paragraphs the chunking splits at - Markdown is
     * read as the text it is, by the text parser.
     */
    private final Parser parser = new AutoDetectParser(
            new DefaultParser(registry, new ServiceLoader(), List.of(RarParser.class, MarkdownParser.class)));

    /** The document's type and text. 400 for an empty or unreadable document, 415 for a type that is not allowed. */
    public ExtractedText extract(String fileName, byte[] content) {
        String type = detect(fileName, content);
        if (!ALLOWED.containsKey(type)) {
            throw new ServiceException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "document.typeNotSupported",
                    "This file type is not supported (" + type + ") - allowed: " + ALLOWED_NAMES,
                    Map.of("type", type));
        }
        return new ExtractedText(type, text(fileName, type, content));
    }

    /**
     * The type Tika recognises from the content (e.g. application/pdf) - before anything is parsed, so a type that is
     * not allowed never is. The name only helps to tell apart what the content leaves open (CSV or Markdown look like
     * plain text) - and a text the content mistakes for another kind of text ({@link #textByName}). 400 for an empty
     * document.
     */
    public String detect(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw ServiceException.badRequest("document.empty", "The document is empty");
        }
        try (TikaInputStream stream = TikaInputStream.get(content)) {
            MediaType detected = detector.detect(stream, nameOnly(fileName), new ParseContext()).getBaseType();
            return textByName(detected, fileName).toString();
        } catch (IOException e) {
            return MediaType.OCTET_STREAM.toString();
        }
    }

    /**
     * A text type not allowed gives way to the allowed text type the name says: Tika's magic takes a CSV opening with
     * "Article" for a Usenet message (message/news), a text opening with "From:" for a mail (message/rfc822). Content
     * that is not text keeps its type, whatever the name.
     */
    private MediaType textByName(MediaType detected, String fileName) {
        if (ALLOWED.containsKey(detected.toString()) || !registry.isSpecializationOf(detected, MediaType.TEXT_PLAIN)) {
            return detected;
        }
        MediaType named;
        try {
            named = byName.detect(null, nameOnly(fileName), new ParseContext()).getBaseType();
        } catch (IOException e) {
            return detected;
        }
        boolean allowedText = ALLOWED.containsKey(named.toString())
                && (named.equals(MediaType.TEXT_PLAIN) || registry.isSpecializationOf(named, MediaType.TEXT_PLAIN));
        return allowedText ? named : detected;
    }

    /**
     * The plain text of a document of the type the caller has already checked ({@link #detect}) - parsed as that type,
     * not as Tika would detect it again. 400 if it cannot be read or has more than {@link #MAX_TEXT_CHARACTERS}
     * characters of text.
     */
    public String text(String fileName, String type, byte[] content) {
        requireModestParts(content);
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_CHARACTERS);
        Metadata metadata = nameOnly(fileName);
        metadata.set(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, type);
        try (TikaInputStream stream = TikaInputStream.get(content)) {
            parser.parse(stream, handler, metadata, outerFileOnly());
        } catch (EncryptedDocumentException e) {
            throw ServiceException.badRequest("document.passwordProtected", "The document is password-protected");
        } catch (Exception e) {
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                throw ServiceException.badRequest("document.tooMuchText", "The document has too much text - at most "
                        + MAX_TEXT_CHARACTERS + " characters",
                        Map.of("max", String.valueOf(MAX_TEXT_CHARACTERS)));
            }
            // The message may quote the document's content - only its kind goes to the log.
            LOG.warn("Document could not be read: {}", e.getClass().getName());
            throw ServiceException.badRequest("document.unreadable", "The document could not be read");
        }
        return handler.toString().strip();
    }

    /**
     * An Office file is a ZIP archive, and a spreadsheet's shared strings are held in memory whole before any text
     * comes out - a file of a few megabytes made for it unpacks into gigabytes there, which the limit of the text
     * never sees. So every part is unpacked once and only counted: 400 for one of more than
     * {@link #MAX_PART_BYTES}. Read by the archive's directory, as the parsers read it.
     */
    private static void requireModestParts(byte[] content) {
        if (content.length < 2 || content[0] != 'P' || content[1] != 'K') {
            return;
        }
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile zip = ZipFile.builder().setByteArray(content).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                long size = 0;
                try (InputStream part = zip.getInputStream(entry)) {
                    for (int read = part.read(buffer); read > 0; read = part.read(buffer)) {
                        size += read;
                        if (size > MAX_PART_BYTES) {
                            throw ServiceException.badRequest("document.partTooLarge", "The document unpacks into more "
                                    + "than " + MAX_PART_BYTES / (1024 * 1024) + " MB",
                                    Map.of("max", String.valueOf(MAX_PART_BYTES / (1024 * 1024))));
                        }
                    }
                }
            }
        } catch (IOException e) {
            // No archive that can be walked: the parser says what the file is.
        }
    }

    private static Metadata nameOnly(String fileName) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        return metadata;
    }

    /**
     * Whether a file of this name may be one of the allowed types - or, when the tenant's image model reads images, an
     * image ({@link DocumentImages#EXTENSIONS}). Only to leave files out before reading them (a knowledge source):
     * whether a file is read is still decided by its content ({@link #detect}).
     */
    public static boolean readableByName(String fileName, boolean imagesRead) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSIONS.contains(extension) || imagesRead && DocumentImages.EXTENSIONS.contains(extension);
    }

    private static Map<String, AllowedType> byMediaType() {
        Map<String, AllowedType> types = new HashMap<>();
        for (AllowedType type : TYPES) {
            types.put(type.mediaType(), type);
        }
        return Map.copyOf(types);
    }

    private static Set<String> extensions() {
        Set<String> extensions = new HashSet<>();
        for (AllowedType type : TYPES) {
            extensions.addAll(type.extensions());
        }
        return Set.copyOf(extensions);
    }

    /** An allowed type: Tika's name (e.g. application/pdf), its name in the error message, its extensions. */
    public record AllowedType(String mediaType, String name, List<String> extensions) {
        AllowedType(String mediaType, String name, String... extensions) {
            this(mediaType, name, List.of(extensions));
        }
    }

    /** The detected type (e.g. application/pdf) and the plain text. */
    public record ExtractedText(String mediaType, String text) {
    }

    /**
     * What a file carries inside - an attachment of a PDF, an object embedded in a DOCX - is not read: Tika would parse
     * it with every parser it has, archives and the old Office formats included, which the agreed types leave out.
     */
    private static ParseContext outerFileOnly() {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new EmptyParser());
        return context;
    }

}
