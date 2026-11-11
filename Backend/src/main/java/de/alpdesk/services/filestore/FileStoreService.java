package de.alpdesk.services.filestore;

import de.alpdesk.dto.filestore.FileStoreCheckResponse;
import de.alpdesk.dto.filestore.FileStoreFoldersResponse;
import de.alpdesk.dto.filestore.FileStoreRequest;
import de.alpdesk.dto.filestore.FileStoreResponse;
import de.alpdesk.entities.filestore.FileStore;
import de.alpdesk.entities.filestore.FileStoreType;
import de.alpdesk.repositories.document.KnowledgeSourceRepository;
import de.alpdesk.repositories.filestore.FileStoreRepository;
import de.alpdesk.repositories.task.TaskRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.security.SecretCipher;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The tenant's file stores (docs/architecture/file-stores.md), kept by its tenant admins and the admin: where a server
 * or bucket is, who logs in, which folder the store stands for - and for SFTP and FTPS the fingerprint of the server's
 * key or certificate a tenant admin confirmed after a check showed it. The password (S3: the secret access key) is
 * stored encrypted and goes to no other server or bucket than the one it was entered for. The type decides how a store
 * is reached - one switch per step, no class per type.
 */
@Singleton
public class FileStoreService {

    private static final Logger LOG = LoggerFactory.getLogger(FileStoreService.class);
    /** The file names a draft of a task is told per store - enough to see how they are named. */
    static final int DRAFT_FILE_NAMES = 20;
    public static final int MAX_NAME = 80;
    /** The subfolders a listing for choosing a folder offers at most. */
    static final int MAX_FOLDERS = 500;
    private static final int MAX_USERNAME = 200;
    private static final int MAX_PASSWORD = 1024;
    private static final int MAX_PATH = 1000;
    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
    /** S3's naming rules for a bucket. */
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern REGION = Pattern.compile("[a-z0-9-]{1,40}");
    /** A compatible store's region when none was given - most of them ignore it, the signature needs one. */
    static final String DEFAULT_REGION = "us-east-1";
    private static final int MAX_ACCESS_KEY_ID = 200;
    /** Entra's IDs (directory, app) are GUIDs. */
    private static final Pattern GUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** A site of the company's SharePoint, or a person's OneDrive (its "-my" host). */
    private static final Pattern SHAREPOINT_HOST = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}\\.sharepoint\\.com");
    private static final int MAX_LIBRARY = 255;
    /** A mailbox's messages listed: the last year's by default, at most ten years'. */
    static final int DEFAULT_DAYS = 365;
    static final int MAX_DAYS = 3650;

    private final FileStoreRepository stores;
    private final TaskRepository tasks;
    private final KnowledgeSourceRepository sources;
    private final TenantRepository tenants;
    private final FileStoreAddresses addresses;
    private final SftpStoreClient sftp;
    private final FtpsStoreClient ftps;
    private final S3StoreClient s3;
    private final GraphStoreClient graph;
    private final MailboxStoreClient mailbox;
    private final SecretCipher cipher;
    private final JsonMapper json;

    public FileStoreService(FileStoreRepository stores, TaskRepository tasks, KnowledgeSourceRepository sources,
                            TenantRepository tenants, FileStoreAddresses addresses, SftpStoreClient sftp,
                            FtpsStoreClient ftps, S3StoreClient s3, GraphStoreClient graph, MailboxStoreClient mailbox,
                            SecretCipher cipher, JsonMapper json) {
        this.stores = stores;
        this.tasks = tasks;
        this.sources = sources;
        this.tenants = tenants;
        this.addresses = addresses;
        this.sftp = sftp;
        this.ftps = ftps;
        this.s3 = s3;
        this.graph = graph;
        this.mailbox = mailbox;
        this.cipher = cipher;
        this.json = json;
    }

    /** The tenant's stores; 404 for an unknown tenant. */
    public List<FileStoreResponse> list(Long tenantId) {
        requireTenant(tenantId);
        return stores.findByTenantIdOrderByName(tenantId).stream().map(this::response).toList();
    }

    /**
     * Saves the store without connecting - a check shows the server's fingerprint, which a tenant admin confirms before
     * the password goes out. 400 for invalid input or a host that is not allowed, 409 for a name the tenant has.
     */
    @Transactional
    public FileStoreResponse create(Long tenantId, FileStoreRequest request) {
        requireTenant(tenantId);
        String name = name(request);
        requireFreeName(tenantId, name, null);
        FileStoreType type = type(request);
        Settings settings = settings(type, request, null);
        String password = password(request.password());
        if (password == null) {
            throw ServiceException.badRequest("fileStore.passwordRequired", "The password is required");
        }
        Instant now = Instant.now();
        return response(stores.save(new FileStore(null, tenantId, name, type, settings.json(),
                cipher.encrypt(password), null, null, taskWrite(type, request.taskWrite(), false), now, now)));
    }

    /**
     * Changes the store. Another server (type, host or port; S3: endpoint, region, bucket or access key; SharePoint:
     * directory or app) is another server: its fingerprint must be confirmed anew, and the password must come again -
     * the stored one is not sent to a server it was not entered for. Other files ({@link Settings#sameFiles()}) are
     * refused with 409 while a knowledge source reads the store - its next listing would delete the source's documents.
     */
    @Transactional
    public Optional<FileStoreResponse> update(Long tenantId, Long storeId, FileStoreRequest request) {
        Optional<FileStore> existing = stores.findByIdAndTenantId(storeId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        FileStore store = existing.get();
        String name = name(request);
        requireFreeName(tenantId, name, storeId);
        FileStoreType type = type(request);
        Settings after = settings(type, request, store);
        if (!after.sameFiles()) {
            requireNotReadBySources(tenantId, storeId);
        }
        String password = password(request.password());
        if (!after.sameServer() && password == null) {
            throw ServiceException.badRequest("fileStore.passwordRequired", "Another server needs the password "
                    + "again - the stored one is not sent to it");
        }
        String secret = password == null ? store.secret() : cipher.encrypt(password);
        boolean unchecked = !after.sameServer() || password != null || !after.json().equals(store.settings());
        return Optional.of(response(stores.update(new FileStore(storeId, tenantId, name, type, after.json(), secret,
                unchecked ? null : store.checkedAt(), unchecked ? null : store.checkError(),
                taskWrite(type, request.taskWrite(), store.taskWrite()), store.createdAt(), Instant.now()))));
    }

    /** Whether tasks may write: as asked, else as it was (false for a new store) - never into a mailbox. */
    private static boolean taskWrite(FileStoreType type, @Nullable Boolean asked, boolean before) {
        if (type == FileStoreType.IMAP) {
            if (Boolean.TRUE.equals(asked)) {
                throw ServiceException.badRequest("fileStore.mailboxReadOnly", "A mailbox is read only - tasks write "
                        + "into another file store");
            }
            return false;
        }
        return asked != null ? asked : before;
    }

    /**
     * The tenant's store, writable now - else 409 task.storeNotWritable: its switch was turned off, or it is no longer
     * ready (another server, not checked) since the task was saved. A write step asks this before it opens the store.
     */
    public void requireWritable(Long tenantId, Long storeId) {
        FileStore store = stores.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("fileStore.notFound", "File store not found"));
        if (!writable(store)) {
            throw ServiceException.conflict("task.storeNotWritable", "Tasks may not write into the file store "
                    + store.name() + " - a tenant admin allows it at the store", Map.of("store", store.name()));
        }
    }

    /**
     * Whether a task may write into the store now: its switch on and the store readable (checked, its fingerprint
     * confirmed) - a write needs the same login.
     */
    public boolean writable(FileStore store) {
        return store.taskWrite() && readable(store);
    }

    /** 409 while a task or a knowledge source reads the store - its runs would fail at their next start. */
    @Transactional
    public boolean delete(Long tenantId, Long storeId) {
        List<String> readBy = tasks.findNamesReadingStore(storeId, tenantId);
        if (!readBy.isEmpty()) {
            throw ServiceException.conflict("fileStore.inUse", "The file store is read by the tasks "
                    + String.join(", ", readBy) + " - change them first", Map.of("tasks", String.join(", ", readBy)));
        }
        requireNotReadBySources(tenantId, storeId);
        return stores.deleteByIdAndTenantId(storeId, tenantId) > 0;
    }

    private void requireNotReadBySources(Long tenantId, Long storeId) {
        List<String> sourcedBy = sources.findNamesReadingStore(storeId, tenantId);
        if (!sourcedBy.isEmpty()) {
            throw ServiceException.conflict("fileStore.usedBySource", "The file store is read by the knowledge sources "
                    + String.join(", ", sourcedBy) + " - change them first",
                    Map.of("sources", String.join(", ", sourcedBy)));
        }
    }

    /**
     * "name (folder; files: ...)" of every store a task may read - what a draft of a task may name, with the newest file
     * names of its folder, so the draft writes a pattern that matches them (names only, never what is in the files). A
     * store out of reach goes without its names. Not in a transaction: a server may take seconds to answer.
     */
    public List<String> storesForDraft(Long tenantId) {
        List<String> described = new ArrayList<>();
        for (FileStore store : stores.findByTenantIdOrderByName(tenantId)) {
            if (readable(store) && store.type() == FileStoreType.IMAP) {
                described.add(store.name() + " (a mailbox: every message a folder, its attachments and mail.txt the "
                        + "files - read with read_documents, subfolders: true)");
            } else if (readable(store)) {
                // A store whose last check failed is not connected to - its timeout would hold the draft for up to
                // half a minute; it goes without its names until a check succeeds again
                String names = store.checkError() == null ? newestFileNames(tenantId, store) : "";
                described.add(store.name() + " (folder " + basePathOf(store) + names + ")");
            }
        }
        return described;
    }

    private String newestFileNames(Long tenantId, FileStore store) {
        List<RemoteFile> files;
        try (FileStoreSession session = open(tenantId, store.id())) {
            files = new ArrayList<>(session.list(basePathOf(store)).stream().filter(file -> !file.folder()).toList());
        } catch (ServiceException e) {
            LOG.info("File names of store {} of tenant {} left out of a draft: {}", store.id(), tenantId, e.code());
            return "";
        }
        if (files.isEmpty()) {
            return "; no files";
        }
        files.sort(Comparator.comparing(RemoteFile::modified, Comparator.nullsLast(Comparator.reverseOrder())));
        List<String> names = new ArrayList<>();
        for (RemoteFile file : files.subList(0, Math.min(DRAFT_FILE_NAMES, files.size()))) {
            names.add(file.name());
        }
        return "; files: " + String.join(", ", names);
    }

    /** The tenant's store of this name (case does not matter), for a task naming it. */
    public Optional<FileStore> findByName(Long tenantId, String name) {
        return stores.findByTenantIdOrderByName(tenantId).stream()
                .filter(store -> store.name().equalsIgnoreCase(name.strip()))
                .findFirst();
    }

    /**
     * Whether a task may read the store - the one rule the tasks ask, decided here by type, so a new type changes
     * nothing in them. SFTP and FTPS: its fingerprint is confirmed, or its last check logged in (an FTPS server whose
     * certificate an authority the installation trusts vouches for needs no fingerprint). S3 and SharePoint: its last
     * check read the folder.
     */
    public boolean readable(FileStore store) {
        boolean checkedOk = store.checkedAt() != null && store.checkError() == null;
        return switch (store.type()) {
            case SFTP, FTPS -> readServer(store).fingerprint() != null || checkedOk;
            case S3, SHAREPOINT, IMAP -> checkedOk;
        };
    }

    /**
     * The absolute folder on the server for a folder relative to the store's own ("logs", "" or "/" for the store's
     * own); null for one that is not a relative path below it (".." or a backslash).
     */
    public static @Nullable String folderBelow(String basePath, String folder) {
        String relative = folder.strip();
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        try {
            String below = basePath("/" + relative);
            return below.equals("/") ? basePath : basePath.equals("/") ? below : basePath + below;
        } catch (ServiceException e) {
            return null;
        }
    }

    /** The store's own folder on the server. */
    public String basePathOf(FileStore store) {
        return switch (store.type()) {
            case SFTP, FTPS -> readServer(store).basePath();
            case S3 -> readS3(store).basePath();
            case SHAREPOINT -> readGraph(store).basePath();
            case IMAP -> "/";
        };
    }

    /** A task's folder (relative, checked by the task's compiler) on the server - below the store's folder as it is now. */
    public String folderOf(Long tenantId, Long storeId, String folder) {
        FileStore store = stores.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("fileStore.notFound", "File store not found"));
        return folderBelow(basePathOf(store), folder);
    }

    /**
     * Connects and logs in to read the store's files (a task's read_files step) - only to the server with the confirmed
     * key or certificate. 404 for a store of another tenant. Not in a transaction: the caller holds the connection for
     * as long as it reads.
     */
    public FileStoreSession open(Long tenantId, Long storeId) {
        FileStore store = stores.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("fileStore.notFound", "File store not found"));
        String secret = cipher.decrypt(store.secret());
        return switch (store.type()) {
            case SFTP, FTPS -> {
                ServerSettings settings = readServer(store);
                InetAddress address = addresses.resolveAllowed(settings.host());
                yield serverClient(store.type()).open(address, settings, secret);
            }
            case S3 -> s3.open(readS3(store), secret);
            case SHAREPOINT -> graph.open(readGraph(store), secret);
            case IMAP -> mailbox.open(readMailbox(store), secret);
        };
    }

    /**
     * The subfolders of a folder of the store (relative to its folder, "" its folder itself) - to choose a knowledge
     * source's folder instead of typing it. One level, sorted, without hidden ones, at most
     * {@link #MAX_FOLDERS}. Only a ready store is connected to (409 fileStore.notReady); 400 for a path not below its
     * folder; a server failing answers its code (502). The names are shown, not stored. Not in a transaction: a server
     * may take seconds to answer.
     */
    public Optional<FileStoreFoldersResponse> folders(Long tenantId, Long storeId, @Nullable String path) {
        Optional<FileStore> found = stores.findByIdAndTenantId(storeId, tenantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FileStore store = found.get();
        String relative = relativeFolder(path);
        if (store.type() == FileStoreType.IMAP) {
            // its folder is set at the store; its messages are no folders to choose
            return Optional.of(new FileStoreFoldersResponse(relative, List.of(), false));
        }
        String absolute = folderBelow(basePathOf(store), relative);
        if (absolute == null) {
            throw ServiceException.badRequest("fileStore.folderInvalid", "The folder is a path below the store's "
                    + "folder, without . or ..");
        }
        if (!readable(store)) {
            throw ServiceException.conflict("fileStore.notReady", "The file store " + store.name() + " is not ready "
                    + "- check it and confirm its server first", Map.of("store", store.name()));
        }
        List<String> names = new ArrayList<>();
        try (FileStoreSession session = open(tenantId, storeId)) {
            for (RemoteFile entry : session.list(absolute)) {
                if (entry.folder() && !RemoteFile.isHidden(entry.name())) {
                    names.add(entry.name());
                }
            }
        }
        boolean truncated = names.size() > MAX_FOLDERS;
        return Optional.of(new FileStoreFoldersResponse(relative,
                truncated ? names.subList(0, MAX_FOLDERS) : names, truncated));
    }

    /** A folder below the store's, as typed: without leading, trailing or doubled slashes; "" for its own. */
    private static String relativeFolder(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String relative = path.strip().replaceAll("/+", "/");
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        while (relative.endsWith("/")) {
            relative = relative.substring(0, relative.length() - 1);
        }
        for (String segment : relative.split("/")) {
            if (segment.equals(".") || hasControlCharacter(segment)) {
                throw ServiceException.badRequest("fileStore.folderInvalid", "The folder is a path below the store's "
                        + "folder, without . or ..");
            }
        }
        return relative;
    }

    /**
     * Connects to the server: shows its fingerprint until one is confirmed, and with a confirmed one logs in and reads
     * the store's folder (S3, SharePoint: reads the folder at once - they have no fingerprint). A failure is the answer (FAILED with
     * its code), not an error - and is kept with the store. Not in a transaction: a server may take seconds to answer.
     */
    public Optional<FileStoreCheckResponse> check(Long tenantId, Long storeId) {
        Optional<FileStore> found = stores.findByIdAndTenantId(storeId, tenantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FileStore store = found.get();
        try {
            CheckResult result = checkByType(store);
            String error = switch (result.status()) {
                case OK -> null;
                case UNCONFIRMED -> "fileStore.fingerprintUnconfirmed";
                case CHANGED -> "fileStore.fingerprintChanged";
            };
            FileStore checked = recordCheck(store, error);
            return Optional.of(new FileStoreCheckResponse(result.status().name(), result.fingerprint(), result.entries(),
                    error, Map.of(), response(checked)));
        } catch (ServiceException e) {
            return Optional.of(failed(store, e.code(), e.params()));
        } catch (RuntimeException e) {
            LOG.warn("Checking file store {} of tenant {} failed: {}", storeId, tenantId, e.toString());
            return Optional.of(failed(store, "fileStore.failed", Map.of()));
        }
    }

    private CheckResult checkByType(FileStore store) {
        String secret = cipher.decrypt(store.secret());
        return switch (store.type()) {
            case SFTP, FTPS -> {
                ServerSettings settings = readServer(store);
                InetAddress address = addresses.resolveAllowed(settings.host());
                yield serverClient(store.type()).check(address, settings, secret);
            }
            case S3 -> s3.check(readS3(store), secret);
            case SHAREPOINT -> graph.check(readGraph(store), secret);
            case IMAP -> mailbox.check(readMailbox(store), secret);
        };
    }

    /**
     * Takes the fingerprint a check showed as the server's - from now on nothing else is logged in to. 409 for an S3 or
     * SharePoint store, which has none.
     */
    @Transactional
    public Optional<FileStoreResponse> confirmFingerprint(Long tenantId, Long storeId, @Nullable String fingerprint) {
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint.strip()).matches()) {
            throw ServiceException.badRequest("fileStore.fingerprintInvalid", "This is not a fingerprint (SHA256:...)");
        }
        Optional<FileStore> found = stores.findByIdAndTenantId(storeId, tenantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FileStore store = found.get();
        if (!store.type().hasFingerprint()) {
            throw ServiceException.conflict("fileStore.noFingerprint", "This store has no fingerprint to confirm - "
                    + "its server proves itself over HTTPS");
        }
        ServerSettings settings = readServer(store).withFingerprint(fingerprint.strip());
        return Optional.of(response(stores.update(new FileStore(store.id(), tenantId, store.name(), store.type(),
                write(settings), store.secret(), null, null, store.taskWrite(), store.createdAt(), Instant.now()))));
    }

    /** The client of a store reached as a server with its fingerprint (SFTP, FTPS). */
    private FileStoreClient serverClient(FileStoreType type) {
        return type == FileStoreType.SFTP ? sftp : ftps;
    }

    private FileStoreCheckResponse failed(FileStore store, String code, Map<String, String> params) {
        return new FileStoreCheckResponse("FAILED", null, List.of(), code, params, response(recordCheck(store, code)));
    }

    /** Only the outcome is written, and not to a store changed meanwhile - it belonged to what the store was. */
    private FileStore recordCheck(FileStore store, @Nullable String error) {
        Instant now = Instant.now();
        stores.updateCheck(store.id(), store.updatedAt(), now, error);
        return new FileStore(store.id(), store.tenantId(), store.name(), store.type(), store.settings(), store.secret(),
                now, error, store.taskWrite(), store.createdAt(), store.updatedAt());
    }

    /**
     * The request's settings as JSON, and two answers against the store's settings before: whether they lead to the same
     * server (S3: bucket, SharePoint: app) - its password and confirmed fingerprint are kept only then -, and whether
     * they lead to the same files - a knowledge source reading the store allows a change only then. The two differ:
     * another S3 access key reads the same files, another SharePoint site through the same app other ones. Both false
     * for a new store or another type.
     */
    private record Settings(String json, boolean sameServer, boolean sameFiles) {
    }

    /** before: the store as it is - null for a new one. */
    private Settings settings(FileStoreType type, FileStoreRequest request, @Nullable FileStore before) {
        return switch (type) {
            case SFTP, FTPS -> serverSettings(type, request, before);
            case S3 -> s3Settings(request, before);
            case SHAREPOINT -> graphSettings(request, before);
            case IMAP -> mailboxSettings(request, before);
        };
    }

    private Settings mailboxSettings(FileStoreRequest request, @Nullable FileStore before) {
        MailboxSettings.Security security = security(request.security());
        if (security == MailboxSettings.Security.NONE && request.host() != null
                && !addresses.plainAllowed(request.host().strip().toLowerCase(Locale.ROOT))) {
            throw ServiceException.badRequest("fileStore.plainImap", "Unencrypted IMAP goes only to the hosts in "
                    + "APP_FILE_STORE_INTERNAL_HOSTS - use SSL or STARTTLS");
        }
        int port = request.port() != null ? port(FileStoreType.IMAP, request.port())
                : security == MailboxSettings.Security.SSL ? 993 : 143;
        MailboxSettings after = new MailboxSettings(addresses.normalize(request.host()), port,
                username(request.username()), security, mailboxFolder(request.mailbox()), days(request.days()));
        if (before == null || before.type() != FileStoreType.IMAP) {
            return new Settings(write(after), false, false);
        }
        MailboxSettings previous = readMailbox(before);
        return new Settings(write(after), after.sameServer(previous), after.sameFiles(previous));
    }

    private static MailboxSettings.Security security(@Nullable String security) {
        if (security == null || security.isBlank()) {
            return MailboxSettings.Security.SSL;
        }
        try {
            return MailboxSettings.Security.valueOf(security.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ServiceException.badRequest("fileStore.securityUnknown", "The security is SSL, STARTTLS or NONE");
        }
    }

    /** The mailbox folder: INBOX when none is given - its name as the server has it, e.g. INBOX/Belege. */
    private static String mailboxFolder(@Nullable String mailbox) {
        if (mailbox == null || mailbox.isBlank()) {
            return "INBOX";
        }
        String stripped = mailbox.strip();
        if (stripped.length() > MAX_LIBRARY || hasControlCharacter(stripped)) {
            throw ServiceException.badRequest("fileStore.mailboxInvalid", "This is not a mailbox folder");
        }
        return stripped;
    }

    private static int days(@Nullable Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        if (days < 1 || days > MAX_DAYS) {
            throw ServiceException.badRequest("fileStore.daysInvalid", "The days lie between 1 and " + MAX_DAYS,
                    Map.of("max", String.valueOf(MAX_DAYS)));
        }
        return days;
    }

    private Settings serverSettings(FileStoreType type, FileStoreRequest request, @Nullable FileStore before) {
        ServerSettings after = new ServerSettings(addresses.normalize(request.host()), port(type, request.port()),
                username(request.username()), basePath(request.basePath()), null);
        if (before == null || before.type() != type) {
            return new Settings(write(after), false, false);
        }
        ServerSettings previous = readServer(before);
        if (!after.sameServer(previous)) {
            return new Settings(write(after), false, false);
        }
        return new Settings(write(after.withFingerprint(previous.fingerprint())), true, after.sameFiles(previous));
    }

    private Settings s3Settings(FileStoreRequest request, @Nullable FileStore before) {
        String endpoint = addresses.normalizeEndpoint(request.endpoint());
        S3Settings after = new S3Settings(endpoint, region(endpoint, request.region()), bucket(request.bucket()),
                basePath(request.basePath()), accessKeyId(request.accessKeyId()));
        if (before == null || before.type() != FileStoreType.S3) {
            return new Settings(write(after), false, false);
        }
        S3Settings previous = readS3(before);
        return new Settings(write(after), after.sameStore(previous), after.sameFiles(previous));
    }

    private Settings graphSettings(FileStoreRequest request, @Nullable FileStore before) {
        GraphSettings after = new GraphSettings(directoryId(request.directoryId()), clientId(request.clientId()),
                siteUrl(request.siteUrl()), library(request.library()), basePath(request.basePath()));
        if (before == null || before.type() != FileStoreType.SHAREPOINT) {
            return new Settings(write(after), false, false);
        }
        GraphSettings previous = readGraph(before);
        return new Settings(write(after), after.sameApp(previous), after.sameFiles(previous));
    }

    /** The customer's Entra ID: a GUID, stored in lower case. */
    private static String directoryId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw ServiceException.badRequest("fileStore.directoryIdRequired", "The directory ID is required");
        }
        String guid = value.strip().toLowerCase(Locale.ROOT);
        if (!GUID.matcher(guid).matches()) {
            throw ServiceException.badRequest("fileStore.directoryIdInvalid", "The directory ID is a GUID like "
                    + "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        }
        return guid;
    }

    /** The app registration's ID: a GUID, stored in lower case. */
    private static String clientId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw ServiceException.badRequest("fileStore.clientIdRequired", "The client ID is required");
        }
        String guid = value.strip().toLowerCase(Locale.ROOT);
        if (!GUID.matcher(guid).matches()) {
            throw ServiceException.badRequest("fileStore.clientIdInvalid", "The client ID is a GUID like "
                    + "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        }
        return guid;
    }

    /**
     * The site's address: https to a host of the company's SharePoint (a OneDrive's too), a path without query or
     * fragment - the host in lower case, no trailing slash.
     */
    private static String siteUrl(@Nullable String siteUrl) {
        if (siteUrl == null || siteUrl.isBlank()) {
            throw ServiceException.badRequest("fileStore.siteUrlRequired", "The site's address is required");
        }
        URI uri;
        try {
            uri = new URI(siteUrl.strip());
        } catch (URISyntaxException e) {
            throw siteUrlInvalid();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean valid = "https".equalsIgnoreCase(uri.getScheme()) && SHAREPOINT_HOST.matcher(host).matches()
                && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getRawPath() != null && uri.getRawPath().length() <= MAX_PATH
                && !uri.getRawPath().contains("/../") && !uri.getRawPath().endsWith("/..");
        if (!valid) {
            throw siteUrlInvalid();
        }
        String path = uri.getRawPath().replaceAll("/+", "/");
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return "https://" + host + path;
    }

    private static ServiceException siteUrlInvalid() {
        return ServiceException.badRequest("fileStore.siteUrlInvalid", "The site's address is like "
                + "https://company.sharepoint.com/sites/projects - a OneDrive's like "
                + "https://company-my.sharepoint.com/personal/someone_company_com");
    }

    /** The library's name; null for none (the site's default library). */
    private static @Nullable String library(@Nullable String library) {
        if (library == null || library.isBlank()) {
            return null;
        }
        String stripped = library.strip();
        if (stripped.length() > MAX_LIBRARY || stripped.contains("/") || hasControlCharacter(stripped)) {
            throw ServiceException.badRequest("fileStore.libraryInvalid", "This is not a library's name");
        }
        return stripped;
    }

    /** Amazon S3 needs its region; a compatible store gets the default one when none was given. */
    private static String region(@Nullable String endpoint, @Nullable String region) {
        if (region == null || region.isBlank()) {
            if (endpoint == null) {
                throw ServiceException.badRequest("fileStore.regionRequired", "Amazon S3 needs the bucket's region, "
                        + "e.g. eu-central-1");
            }
            return DEFAULT_REGION;
        }
        String stripped = region.strip().toLowerCase(Locale.ROOT);
        if (!REGION.matcher(stripped).matches()) {
            throw ServiceException.badRequest("fileStore.regionInvalid", "This is not a region, e.g. eu-central-1");
        }
        return stripped;
    }

    private static String bucket(@Nullable String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw ServiceException.badRequest("fileStore.bucketRequired", "The bucket is required");
        }
        String stripped = bucket.strip();
        if (!BUCKET.matcher(stripped).matches()) {
            throw ServiceException.badRequest("fileStore.bucketInvalid", "A bucket's name has 3 to 63 lower case "
                    + "letters, digits, dots and hyphens");
        }
        return stripped;
    }

    private static String accessKeyId(@Nullable String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            throw ServiceException.badRequest("fileStore.accessKeyRequired", "The access key ID is required");
        }
        String stripped = accessKeyId.strip();
        boolean invalid = stripped.length() > MAX_ACCESS_KEY_ID || hasControlCharacter(stripped)
                || stripped.chars().anyMatch(Character::isWhitespace);
        if (invalid) {
            throw ServiceException.badRequest("fileStore.accessKeyInvalid", "This is not an access key ID");
        }
        return stripped;
    }

    private static String name(FileStoreRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ServiceException.badRequest("fileStore.nameRequired", "The name is required");
        }
        String name = request.name().strip();
        if (name.length() > MAX_NAME) {
            throw ServiceException.badRequest("fileStore.nameTooLong", "The name may have at most " + MAX_NAME
                    + " characters", Map.of("max", String.valueOf(MAX_NAME)));
        }
        return name;
    }

    private void requireFreeName(Long tenantId, String name, @Nullable Long storeId) {
        boolean taken = stores.findByTenantIdOrderByName(tenantId).stream()
                .anyMatch(other -> other.name().equalsIgnoreCase(name) && !other.id().equals(storeId));
        if (taken) {
            throw ServiceException.conflict("fileStore.nameTaken", "The tenant has a file store of this name",
                    Map.of("name", name));
        }
    }

    private static FileStoreType type(FileStoreRequest request) {
        try {
            return FileStoreType.valueOf(String.valueOf(request.type()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ServiceException.badRequest("fileStore.typeUnknown", "The type is SFTP, FTPS, S3, SHAREPOINT or IMAP");
        }
    }

    private static int port(FileStoreType type, @Nullable Integer port) {
        if (port == null) {
            return type == FileStoreType.SFTP ? 22 : type == FileStoreType.IMAP ? 993 : 21;
        }
        if (port < 1 || port > 65535) {
            throw ServiceException.badRequest("fileStore.portInvalid", "The port lies between 1 and 65535");
        }
        return port;
    }

    private static String username(@Nullable String username) {
        if (username == null || username.isBlank()) {
            throw ServiceException.badRequest("fileStore.usernameRequired", "The user name is required");
        }
        String stripped = username.strip();
        if (stripped.length() > MAX_USERNAME || hasControlCharacter(stripped)) {
            throw ServiceException.badRequest("fileStore.usernameInvalid", "This is not a user name");
        }
        return stripped;
    }

    /** Null: none given. Kept as entered - a password may begin or end with a space. */
    private static String password(@Nullable String password) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        if (password.length() > MAX_PASSWORD || hasControlCharacter(password)) {
            throw ServiceException.badRequest("fileStore.passwordInvalid", "This is not a password");
        }
        return password;
    }

    /**
     * The folder the store stands for: absolute, without "." or ".." - a store never reaches above it. Left out: "/".
     */
    static String basePath(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String stripped = path.strip();
        boolean invalid = !stripped.startsWith("/") || stripped.length() > MAX_PATH || stripped.contains("\\")
                || hasControlCharacter(stripped);
        for (String segment : stripped.split("/")) {
            invalid |= segment.equals(".") || segment.equals("..");
        }
        if (invalid) {
            throw ServiceException.badRequest("fileStore.pathInvalid", "The folder is an absolute path like /logs, "
                    + "without . or ..");
        }
        String normalized = stripped.replaceAll("/+", "/");
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private FileStoreResponse response(FileStore store) {
        return switch (store.type()) {
            case SFTP, FTPS -> {
                ServerSettings server = readServer(store);
                yield new FileStoreResponse(store.id(), store.name(), store.type(), server.host(), server.port(),
                        server.username(), server.basePath(), server.fingerprint(), null, null, null, null, null, null,
                        null, null, store.checkedAt(), store.checkError(), store.taskWrite(), null, null, null);
            }
            case S3 -> {
                S3Settings bucket = readS3(store);
                yield new FileStoreResponse(store.id(), store.name(), store.type(), null, null, null,
                        bucket.basePath(), null, bucket.endpoint(), bucket.region(), bucket.bucket(),
                        bucket.accessKeyId(), null, null, null, null, store.checkedAt(), store.checkError(),
                        store.taskWrite(), null, null, null);
            }
            case SHAREPOINT -> {
                GraphSettings site = readGraph(store);
                yield new FileStoreResponse(store.id(), store.name(), store.type(), null, null, null,
                        site.basePath(), null, null, null, null, null, site.directoryId(), site.clientId(),
                        site.siteUrl(), site.library(), store.checkedAt(), store.checkError(), store.taskWrite(),
                        null, null, null);
            }
            case IMAP -> {
                MailboxSettings box = readMailbox(store);
                yield new FileStoreResponse(store.id(), store.name(), store.type(), box.host(), box.port(),
                        box.username(), "/", null, null, null, null, null, null, null, null, null, store.checkedAt(),
                        store.checkError(), false, box.security().name(), box.mailbox(), box.days());
            }
        };
    }

    private String write(Object settings) {
        try {
            return json.writeValueAsString(settings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ServerSettings readServer(FileStore store) {
        return read(store.settings(), ServerSettings.class);
    }

    private S3Settings readS3(FileStore store) {
        return read(store.settings(), S3Settings.class);
    }

    private GraphSettings readGraph(FileStore store) {
        return read(store.settings(), GraphSettings.class);
    }

    private MailboxSettings readMailbox(FileStore store) {
        return read(store.settings(), MailboxSettings.class);
    }

    private <T> T read(String settings, Class<T> type) {
        try {
            return json.readValue(settings, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void requireTenant(Long tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw ServiceException.notFound("tenant.notFound", "Tenant not found");
        }
    }
}
