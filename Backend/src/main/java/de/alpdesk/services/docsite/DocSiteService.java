package de.alpdesk.services.docsite;

import de.alpdesk.entities.docsite.DocSite;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.docsite.DocSiteRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentArchive;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.document.KnowledgeScope;
import de.alpdesk.services.tenant.TenantDatabases;
import de.alpdesk.services.tenant.TenantService;
import de.alpdesk.storage.FileStorage;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.format.ReadableBytes;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The handbook (docs/architecture/doc-sites.md): one static website per scope - the whole tenant or one knowledge area
 * -, uploaded as a ZIP by the tenant admins and the admin, read by the users of that scope in an iframe. Its files are
 * served sandboxed (DocSiteFilesController), never as a page of the app. The assistant does not know them.
 * <p>
 * No @Transactional: the tenant database is reached by plain JDBC, which it does not cover - a replacement is one JDBC
 * transaction of its own (DocSiteRepository.replace), a deletion one statement. The files are written before the row
 * points at them and deleted after it no longer does.
 */
@Singleton
public class DocSiteService {

    private static final Logger LOG = LoggerFactory.getLogger(DocSiteService.class);

    static final Duration SESSION = Duration.ofHours(1);
    static final String INDEX = "index.html";

    /** What a website may hold, by extension, and the type it is served with (nosniff: the type is final). */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html;charset=utf-8"),
            Map.entry("htm", "text/html;charset=utf-8"),
            Map.entry("css", "text/css;charset=utf-8"),
            Map.entry("js", "text/javascript;charset=utf-8"),
            Map.entry("mjs", "text/javascript;charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain;charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"));

    private final DocSiteRepository sites;
    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final KnowledgeAreaService areas;
    private final FileStorage storage;
    private final DocSiteTokens tokens;
    private final long maxZipBytes;
    private final DocumentArchive.Limits limits;

    /**
     * The ZIP's limit is app.doc-site.max-zip-size; unpacked: 2000 files, a single file like a document, all together
     * five times the ZIP - enough for well-packed text, and still a guard against a zip bomb.
     */
    public DocSiteService(DocSiteRepository sites, TenantRepository tenants, TenantDatabases databases,
                          KnowledgeAreaService areas, FileStorage storage, DocSiteTokens tokens,
                          @Value("${app.doc-site.max-zip-size}") @ReadableBytes long maxZipBytes,
                          @Value("${micronaut.server.max-request-size}") @ReadableBytes long maxRequestBytes) {
        this.sites = sites;
        this.tenants = tenants;
        this.databases = databases;
        this.areas = areas;
        this.storage = storage;
        this.tokens = tokens;
        this.maxZipBytes = zipLimit(maxZipBytes, maxRequestBytes);
        this.limits = new DocumentArchive.Limits(2000, 50 * 1024 * 1024, 5 * this.maxZipBytes);
    }

    /** A limit above the server's request limit is capped at it: a larger ZIP would fail at the server, unexplained. */
    static long zipLimit(long configured, long requestLimit) {
        if (configured <= requestLimit) {
            return configured;
        }
        LOG.warn("app.doc-site.max-zip-size ({} bytes) lies above the server's request limit ({} bytes) - capped at it",
                configured, requestLimit);
        return requestLimit;
    }

    /** The largest ZIP taken - the upload dialog refuses a larger one before it is sent. */
    public long maxZipBytes() {
        return maxZipBytes;
    }

    /** Every website of the tenant - for its tenant admins and the admin. */
    public List<DocSite> findAll(Long tenantId) {
        return sites.findAll(dataSource(tenant(tenantId)));
    }

    /** The websites a tenant user reads: the whole tenant's and those of its areas; a tenant admin's every one. */
    public List<DocSite> findReadable(Long tenantId, Long userId) {
        KnowledgeScope scope = areas.scopeOfUser(tenantId, userId);
        return findAll(tenantId).stream().filter(site -> scope.includes(site.areaId())).toList();
    }

    /**
     * Replaces the scope's website (areaId null: the whole tenant's) with the ZIP's - written beside the old one, then
     * swapped, then the old files deleted: a reader never sees half of each. A ZIP whose files all sit in one folder
     * (a zipped folder) is served from that folder. 400 for an unknown area, a ZIP too large, without index.html or
     * that is no ZIP.
     */
    public Upload replace(Long tenantId, @Nullable Long areaId, long zipBytes, Path zip) {
        Tenant tenant = tenant(tenantId);
        Long area = areas.requireArea(tenantId, areaId);
        if (zipBytes > maxZipBytes) {
            String maxMb = String.valueOf(maxZipBytes / (1024 * 1024));
            throw ServiceException.badRequest("docSite.tooLarge", "The ZIP may have at most " + maxMb + " MB",
                    Map.of("maxMb", maxMb));
        }
        String directory = TenantService.storagePrefix(tenantId) + "doc-sites/" + UUID.randomUUID() + "/";
        try {
            Unpacked unpacked = unpack(zip, directory);
            String root = rootOf(unpacked.paths());
            DocSiteRepository.Replaced replaced = sites.replace(dataSource(tenant), area, directory + root,
                    unpacked.paths().size(), unpacked.sizeBytes(), Instant.now());
            deleteFilesQuietly(replaced.replacedPrefix());
            return new Upload(replaced.site(), unpacked.skipped());
        } catch (RuntimeException e) {
            deleteFilesQuietly(directory);
            throw e;
        }
    }

    /** Deletes the website and its files; false for an unknown one. */
    public boolean delete(Long tenantId, Long siteId) {
        Optional<String> prefix = sites.delete(dataSource(tenant(tenantId)), siteId);
        prefix.ifPresent(this::deleteFilesQuietly);
        return prefix.isPresent();
    }

    /**
     * The website's address for an hour, below the API's base: {@code doc-sites/<token>/}. Only for a website the
     * scope includes - 404 otherwise, like an unknown one.
     */
    public String address(Long tenantId, Long siteId, KnowledgeScope scope) {
        DocSite site = sites.findById(dataSource(tenant(tenantId)), siteId)
                .filter(found -> scope.includes(found.areaId()))
                .orElseThrow(() -> ServiceException.notFound("docSite.notFound", "Website not found"));
        return "doc-sites/" + tokens.create(tenantId, site.id(), Instant.now().plus(SESSION)) + "/";
    }

    /** As {@link #address(Long, Long, KnowledgeScope)} for a tenant user: the websites it reads. */
    public String addressForUser(Long tenantId, Long userId, Long siteId) {
        return address(tenantId, siteId, areas.scopeOfUser(tenantId, userId));
    }

    /** Whether the token is one this installation signed and still valid - nothing more is read for it. */
    public boolean signed(String token) {
        return tokens.verify(token).isPresent();
    }

    /**
     * A file of the website the token opens - the path below the website, "" or a folder ending in "/" meaning its
     * index.html. A folder named without its "/" answers {@link Served.Folder}, so relative links inside it resolve.
     * Empty for a bad token, an unknown website or file, and a path leaving the website.
     */
    public Optional<Served> open(String token, String path) {
        Optional<DocSiteTokens.Grant> grant = tokens.verify(token);
        if (grant.isEmpty() || !validPath(path)) {
            return Optional.empty();
        }
        Optional<DocSite> site = tenants.findById(grant.get().tenantId())
                .filter(Tenant::active)
                .flatMap(tenant -> sites.findById(dataSource(tenant), grant.get().siteId()));
        if (site.isEmpty()) {
            return Optional.empty();
        }
        String file = path.isEmpty() || path.endsWith("/") ? path + INDEX : path;
        String type = typeOf(file);
        if (type != null) {
            Optional<InputStream> content = storage.open(site.get().storagePrefix() + file);
            if (content.isPresent()) {
                return Optional.of(new Served.File(content.get(), type));
            }
        }
        if (!path.isEmpty() && !path.endsWith("/")) {
            Optional<InputStream> index = storage.open(site.get().storagePrefix() + path + "/" + INDEX);
            if (index.isPresent()) {
                closeQuietly(index.get());
                return Optional.of(new Served.Folder(folderLocation(path.substring(path.lastIndexOf('/') + 1))));
            }
        }
        return Optional.empty();
    }

    /** Writes the archive's files of an allowed type below the directory; the others are skipped with their reason. */
    private Unpacked unpack(Path zip, String directory) {
        List<String> paths = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        long[] size = {0};
        DocumentArchive.read(zip, limits, new DocumentArchive.Handler() {
            @Override
            public void file(String path, byte[] content) {
                String type = typeOf(path);
                if (type == null) {
                    skipped.add(new Skipped(path, "type not allowed"));
                    return;
                }
                storage.put(directory + path, content, type);
                paths.add(path);
                size[0] += content.length;
            }

            @Override
            public void skipped(String path, String reason) {
                skipped.add(new Skipped(path, reason));
            }
        });
        return new Unpacked(paths, size[0], skipped);
    }

    /** "" when index.html is at the top, "folder/" when every file is in one folder with its index.html; else 400. */
    static String rootOf(List<String> paths) {
        Set<String> all = new HashSet<>(paths);
        if (all.contains(INDEX)) {
            return "";
        }
        Set<String> folders = new HashSet<>();
        for (String path : paths) {
            int slash = path.indexOf('/');
            folders.add(slash < 0 ? "" : path.substring(0, slash + 1));
        }
        if (folders.size() == 1 && !folders.contains("") && all.contains(folders.iterator().next() + INDEX)) {
            return folders.iterator().next();
        }
        throw ServiceException.badRequest("docSite.noIndex", "The ZIP has no index.html at its top");
    }

    /** A path inside the website: nothing leaving it, nothing hidden - the rules of the archive itself. */
    static boolean validPath(String path) {
        if (path.isEmpty()) {
            return true;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return !path.contains("\\") && !path.startsWith("/") && !trimmed.contains("//")
                && DocumentArchive.refusal(trimmed) == null;
    }

    /**
     * Where a folder named without its "/" is redirected: "./" and its name with "/", percent-encoded - a space or an
     * umlaut is no character of an address (a folder "Unser Team" failed with 500), and "./" keeps a name with a colon
     * from being read as a scheme.
     */
    static String folderLocation(String name) {
        try {
            return new URI(null, null, "./" + name + "/", null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a folder name: " + name, e);
        }
    }

    /** The type a file is served with; null for a file a website may not hold. */
    static @Nullable String typeOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Deletes the files of a website's directory - a failure only leaves files nobody reaches, so it is logged. */
    private void deleteFilesQuietly(@Nullable String prefix) {
        if (prefix == null) {
            return;
        }
        String directory = directoryOf(prefix);
        try {
            storage.deleteAll(directory);
        } catch (RuntimeException e) {
            LOG.warn("Files of a replaced website could not be removed ({}): {}", directory, e.toString());
        }
    }

    /** The upload's own directory ("tenants/7/doc-sites/<uuid>/") of a prefix that may point into its folder. */
    static String directoryOf(String prefix) {
        String[] segments = prefix.split("/");
        return String.join("/", segments[0], segments[1], segments[2], segments[3]) + "/";
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Only opened to see that the file is there.
        }
    }

    private Tenant tenant(Long tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ServiceException.notFound("tenant.notFound",
                "Tenant not found"));
    }

    private DataSource dataSource(Tenant tenant) {
        return databases.dataSource(tenant.databaseName());
    }

    private record Unpacked(List<String> paths, long sizeBytes, List<Skipped> skipped) {
    }

    /** A file of the ZIP that is not part of the website, and why. */
    public record Skipped(String path, String reason) {
    }

    /** The stored website and the ZIP's files left out. */
    public record Upload(DocSite site, List<Skipped> skipped) {
    }

    /** What a website's address answers: a file, or a folder to be named with its "/". */
    public sealed interface Served {

        /** The caller closes the content. */
        record File(InputStream content, String contentType) implements Served {
        }

        /** Where to go instead: the folder's last segment with "/", encoded - relative to the address asked for. */
        record Folder(String location) implements Served {
        }
    }
}
