package de.alpdesk.services.filestore;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * A connection to a store, logged in - opened only when the server presented its confirmed key or certificate. Paths
 * are absolute on the server, below the store's folder. Failures are ServiceExceptions with a fileStore.* code and 502.
 */
public interface FileStoreSession extends AutoCloseable {

    /**
     * Entries of one folder listed at most - a folder with more is refused (fileStore.tooManyEntries): a server a
     * tenant admin runs could otherwise answer without end and fill the memory every tenant shares.
     */
    int MAX_ENTRIES = 50_000;

    /** The files and folders of a folder, sorted by name - without "." and "..". */
    List<RemoteFile> list(String folder);

    /** At most maxBytes of the file from offset on - fewer at its end, none past it. */
    byte[] read(String path, long offset, int maxBytes);

    /**
     * Creates the file or replaces it whole - never half written for a reader (a temporary file renamed into place
     * where the server has files; an object or an item replaced at once where it has none). Its folder must exist; no
     * folder is made, nothing deleted or renamed besides. Only for a store whose switch lets tasks write - the caller
     * checks it (docs/architecture/file-stores.md "Writing").
     */
    void write(String path, byte[] content);

    /**
     * The whole file in one read of its listed size and a byte more - read to its end, an FTPS transfer is not broken
     * off (which ends the session on some servers). A file grown since it was listed is read on in parts, at most a byte
     * beyond maxBytes, so the caller refuses it as too large rather than reading without end.
     */
    default byte[] readWhole(String path, long listedSize, int maxBytes) {
        int first = (int) Math.min(listedSize + 1, maxBytes + 1L);
        byte[] start = read(path, 0, first);
        if (start.length < first) {
            return start;
        }
        int chunk = 8 * 1024 * 1024;
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(start);
        while (content.size() <= maxBytes) {
            byte[] part = read(path, content.size(), chunk);
            content.writeBytes(part);
            if (part.length < chunk) {
                break;
            }
        }
        return content.toByteArray();
    }

    @Override
    void close();
}
