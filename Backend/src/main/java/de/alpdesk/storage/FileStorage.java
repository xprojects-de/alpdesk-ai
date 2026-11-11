package de.alpdesk.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * Where binary files (the files of the chat conversations) live. The database only stores the key. Implementations are
 * chosen by {@code app.storage.type} - today {@code local} ({@link LocalFileStorage}); an S3 or
 * other backend is one more implementation with its own {@code @Requires(property = "app.storage.type", value = "...")}.
 * <p>
 * Keys are relative, slash-separated paths chosen by the caller (e.g.
 * {@code tenants/7/chat/<uuid>/file}) and must not contain {@code ..} segments.
 */
public interface FileStorage {

    /** Stores the content under the key, replacing an existing file. */
    void put(String key, byte[] content, String contentType);

    /** Stores what the stream holds under the key, replacing an existing file - a video, never whole in memory. */
    void put(String key, InputStream content, String contentType);

    /** The content, or empty if there is no file under the key. The caller closes the stream. */
    Optional<InputStream> open(String key);

    /** Deletes the file; no-op if it does not exist. */
    void delete(String key);

    /** Deletes every file whose key starts with the prefix (a "directory" like {@code tenants/7/}). */
    void deleteAll(String prefix);
}
