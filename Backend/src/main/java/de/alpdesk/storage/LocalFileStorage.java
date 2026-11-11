package de.alpdesk.storage;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * {@link FileStorage} on the local file system under {@code app.storage.local.path} (in the
 * container a volume, see Backend/podman). Writes go to a temp file first and are moved into place,
 * so a reader never sees a half-written file.
 */
@Singleton
@Requires(property = "app.storage.type", value = "local", defaultValue = "local")
public class LocalFileStorage implements FileStorage {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    public LocalFileStorage(@Value("${app.storage.local.path}") String path) {
        this.root = Path.of(path).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create file storage directory " + root, e);
        }
        LOG.info("File storage: {}", root);
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        put(key, new ByteArrayInputStream(content), contentType);
    }

    @Override
    public void put(String key, InputStream content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file " + key, e);
        }
    }

    @Override
    public Optional<InputStream> open(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete file " + key, e);
        }
    }

    @Override
    public void deleteAll(String prefix) {
        Path directory = resolve(prefix);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete files under " + prefix, e);
        }
    }

    /** The file for a key - never outside the storage root. */
    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (key.isBlank() || !file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return file;
    }
}
