package de.alpdesk.services.task

import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.filestore.FileStoreSession
import de.alpdesk.services.filestore.RemoteFile
import de.alpdesk.services.filestore.ServerSettings
import de.alpdesk.services.filestore.SftpStoreClient
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpStatus
import jakarta.inject.Singleton

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An SFTP store in memory for the task specs (test.fake-store=true): files by their absolute path, logged in only with
 * the fingerprint FINGERPRINT confirmed - so a run reads without a server. The real clients are in FileStoreServerSpec.
 */
@Singleton
@Replaces(SftpStoreClient)
@Requires(property = "test.fake-store", value = "true")
class FakeStoreClient extends SftpStoreClient {

    static final String FINGERPRINT = "SHA256:" + "F" * 43

    final Map<String, byte[]> files = new ConcurrentHashMap<>()
    final Map<String, Instant> modified = new ConcurrentHashMap<>()
    /** The folders there are besides those of the files - like the real clients, listing another one fails. */
    final Set<String> folders = ConcurrentHashMap.<String> newKeySet().tap { add(TaskApi.LOGS) }
    volatile boolean unreachable = false
    /** The key the server presents - another than the confirmed one gets no login. */
    volatile String presented = FINGERPRINT
    /** While set, every read waits for it - a run stays open as long as a spec wants. */
    volatile CountDownLatch hold = null
    /** How often a session was opened (or refused). */
    volatile int opened = 0
    /** How many reads began - a spec waits for the first while hold keeps it. */
    final AtomicInteger reads = new AtomicInteger()
    /** Files the server lists but refuses to read (no right to them). */
    final Set<String> unreadable = ConcurrentHashMap.<String> newKeySet()
    /** Files the server refuses to write (no right to them). */
    final Set<String> unwritable = ConcurrentHashMap.<String> newKeySet()
    /** How many writes went through. */
    final AtomicInteger written = new AtomicInteger()

    void reset() {
        files.clear()
        modified.clear()
        folders.clear()
        unreadable.clear()
        unwritable.clear()
        written.set(0)
        folders.add(TaskApi.LOGS)
        unreachable = false
        presented = FINGERPRINT
        hold = null
        opened = 0
        reads.set(0)
    }

    void write(String path, String content) {
        files[path] = content.getBytes("UTF-8")
        modified[path] = Instant.now()
    }

    /** A file of any content - a document - with the time the server lists for it. */
    void put(String path, byte[] content, Instant time = Instant.now()) {
        files[path] = content
        modified[path] = time
    }

    void append(String path, String content) {
        write(path, new String(files[path] ?: new byte[0], "UTF-8") + content)
    }

    @Override
    FileStoreSession open(InetAddress address, ServerSettings settings, String password) {
        opened++
        if (unreachable) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.unreachable", "The server could not be reached",
                    [host: settings.host()])
        }
        if (settings.fingerprint() != presented) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.fingerprintChanged", "Another key")
        }
        new FileStoreSession() {
            @Override
            List<RemoteFile> list(String folder) {
                String prefix = folder.endsWith("/") ? folder : folder + "/"
                if (!folders.contains(folder) && !files.keySet().any { it.startsWith(prefix) }) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound",
                            "The folder " + folder + " does not exist or may not be read", [path: folder])
                }
                List<String> inside = (files.keySet() + folders).findAll { it.startsWith(prefix) }
                        .collect { it.substring(prefix.length()) }
                def fileEntries = inside.findAll { !it.contains("/") && files.containsKey(prefix + it) }
                        .collect { new RemoteFile(it, files[prefix + it].length, modified[prefix + it], false) }
                // A folder below: named by a deeper file's path, or one of the folders there are
                def folderEntries = inside.findAll { it.contains("/") || folders.contains(prefix + it) }
                        .collect { it.split("/")[0] }.unique().collect { new RemoteFile(it, 0, null, true) }
                (fileEntries + folderEntries).sort { it.name() }
            }

            @Override
            byte[] read(String path, long offset, int maxBytes) {
                reads.incrementAndGet()
                hold?.await(20, TimeUnit.SECONDS)
                if (unreadable.contains(path)) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.readFailed",
                            "Reading " + path + " on the file store failed", [path: path])
                }
                byte[] content = files[path]
                if (offset >= content.length) {
                    return new byte[0]
                }
                Arrays.copyOfRange(content, (int) offset, (int) Math.min(content.length, offset + maxBytes))
            }

            @Override
            void write(String path, byte[] content) {
                if (unwritable.contains(path)) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.writeFailed",
                            "Writing " + path + " failed: permission denied", [path: path])
                }
                files[path] = content
                modified[path] = Instant.now()
                written.incrementAndGet()
            }

            @Override
            void close() {
            }
        }
    }
}
