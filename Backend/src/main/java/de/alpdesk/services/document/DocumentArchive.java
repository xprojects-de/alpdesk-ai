package de.alpdesk.services.document;

import de.alpdesk.services.common.ServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads a ZIP archive entry by entry, never all at once - one of documents (docs/architecture/ai-features.md
 * "Importing a ZIP archive") or a handbook's website (docs/architecture/doc-sites.md), each with its {@link Limits}. A
 * ZIP may be a zip bomb, so its own size claims are not trusted: every entry is read up to its limit and the whole up to
 * the total, counted while reading; an archive with more entries than allowed is refused. Paths leaving the archive ("..") and hidden files (".DS_Store",
 * "__MACOSX/") are skipped - nothing is ever written to disk under an entry's name -, and so is an entry that cannot be
 * unpacked (damaged, encrypted).
 */
public final class DocumentArchive {

    /** A document archive's entries are as large as a single upload may be. */
    public static final Limits DOCUMENTS = new Limits(5000, DocumentService.MAX_DOCUMENT_BYTES, 2048L * 1024 * 1024);
    static final int MAX_PATH = 500;
    /** Windows' own ZIP tools write names in this code page without saying so. */
    private static final Charset LEGACY_NAMES = Charset.forName("IBM437");

    private DocumentArchive() {
    }

    /** At most this many entries, each up to maxEntryBytes, all together up to maxTotalBytes unpacked. */
    public record Limits(int maxEntries, int maxEntryBytes, long maxTotalBytes) {
    }

    /** What is done with each entry. */
    public interface Handler {

        /** A file of the archive: its path ("Folder/Sub/name.pdf") and content. */
        void file(String path, byte[] content);

        /** An entry that is not read, and why. */
        void skipped(String path, String reason);
    }

    /** A document archive - see {@link #read(Path, Limits, Handler)}. */
    public static void read(Path zip, Handler handler) {
        read(zip, DOCUMENTS, handler);
    }

    /** Reads the archive's files in order. 400 for a file that is no ZIP, one with too many entries or too large. */
    public static void read(Path zip, Limits limits, Handler handler) {
        try (ZipFile archive = open(zip)) {
            if (archive.size() > limits.maxEntries()) {
                throw ServiceException.badRequest("archive.tooManyEntries", "The archive may have at most "
                        + limits.maxEntries() + " entries",
                        Map.of("max", String.valueOf(limits.maxEntries())));
            }
            long total = 0;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String path = normalize(entry.getName());
                String refused = refusal(path);
                if (refused != null) {
                    handler.skipped(entry.getName(), refused);
                    continue;
                }
                byte[] content = unpacked(archive, entry, limits.maxEntryBytes());
                if (content == null) {
                    // Damaged, encrypted or packed in a way Java does not read - the others are still read.
                    handler.skipped(path, "could not be unpacked");
                    continue;
                }
                if (content.length > limits.maxEntryBytes()) {
                    handler.skipped(path, "larger than " + limits.maxEntryBytes() / (1024 * 1024) + " MB");
                    continue;
                }
                total += content.length;
                if (total > limits.maxTotalBytes()) {
                    long maxMb = limits.maxTotalBytes() / (1024 * 1024);
                    throw ServiceException.badRequest("archive.tooLarge", "The archive unpacks to more than "
                            + maxMb + " MB", Map.of("maxMb", String.valueOf(maxMb)));
                }
                handler.file(path, content);
            }
        } catch (IOException e) {
            throw ServiceException.badRequest("archive.unreadable",
                    "The archive could not be read - is it a ZIP file?");
        }
    }

    /** The entry's content, at most one byte beyond maxBytes; null when it cannot be unpacked. */
    private static byte[] unpacked(ZipFile archive, ZipEntry entry, int maxBytes) {
        try (InputStream in = archive.getInputStream(entry)) {
            return in.readNBytes(maxBytes + 1);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * UTF-8 names (what macOS and most tools write), else the legacy code page. A name that is no UTF-8 fails when the
     * archive is opened (the JDK checks every name of the listing then) or, in older JDKs, when the entries are listed -
     * either way here, not halfway through. An archive that is no ZIP fails both ways.
     */
    private static ZipFile open(Path zip) throws IOException {
        try {
            ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8);
            try {
                archive.stream().forEach(_ -> { });
                return archive;
            } catch (IllegalArgumentException e) {
                archive.close();
            }
        } catch (ZipException e) {
            // Tried again with the legacy code page below.
        }
        return new ZipFile(zip.toFile(), LEGACY_NAMES);
    }

    /** Forward slashes, no leading slash or "./". */
    static String normalize(String name) {
        String path = name.replace('\\', '/');
        while (path.startsWith("/") || path.startsWith("./")) {
            path = path.substring(path.startsWith("/") ? 1 : 2);
        }
        return path;
    }

    /** Why the path is not read; null when it is. Also the rule of a path inside a handbook's website. */
    public static String refusal(String path) {
        if (path.isEmpty() || path.length() > MAX_PATH) {
            return "invalid path";
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return "invalid path";
            }
            if (segment.startsWith(".") || segment.equals("__MACOSX")) {
                return "hidden file";
            }
        }
        return null;
    }
}
