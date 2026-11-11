package de.alpdesk.services.branding;

import de.alpdesk.entities.branding.TenantBranding;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.branding.TenantBrandingRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.TenantService;
import de.alpdesk.storage.FileStorage;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The look of the frontend (docs/architecture/branding.md): the installation's default from app.branding.* - the
 * admin's dashboard shows it - and a tenant's own, set by the admin, shown in the tenant area and on the tenant's own
 * login page. A tenant's value left empty falls back to the default, value by value.
 */
@Singleton
public class BrandingService {

    public static final int MAX_NAME = 60;
    public static final int MAX_LOGO_BYTES = 512 * 1024;
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String CLASSPATH = "classpath:";

    private final TenantBrandingRepository repository;
    private final TenantRepository tenants;
    private final FileStorage storage;
    private final String defaultName;
    private final String defaultColor;
    private final Logo defaultLogo;

    /** Refuses to start with an invalid default - a typo in the environment shows at once, not in a customer's browser. */
    public BrandingService(TenantBrandingRepository repository, TenantRepository tenants, FileStorage storage,
                           @Value("${app.branding.name}") String defaultName,
                           @Value("${app.branding.primary-color}") String defaultColor,
                           @Value("${app.branding.logo:}") String defaultLogoPath) {
        this.repository = repository;
        this.tenants = tenants;
        this.storage = storage;
        this.defaultName = requireDefaultName(defaultName);
        this.defaultColor = requireDefaultColor(defaultColor);
        this.defaultLogo = defaultLogoPath.isBlank() ? null : readDefaultLogo(defaultLogoPath.strip());
    }

    /** The installation's look - for the admin's dashboard. */
    public Branding installation() {
        return new Branding(defaultName, defaultColor, defaultLogo != null);
    }

    public Optional<Logo> installationLogo() {
        return Optional.ofNullable(defaultLogo);
    }

    /** The tenant's look, each value falling back to the installation's. */
    public Branding forTenant(Long tenantId) {
        Optional<TenantBranding> own = repository.findById(tenantId);
        return new Branding(
                own.map(TenantBranding::name).orElse(defaultName),
                own.map(TenantBranding::primaryColor).orElse(defaultColor),
                own.map(TenantBranding::logoType).isPresent() || defaultLogo != null);
    }

    /** The tenant's logo, else the installation's. */
    public Optional<Logo> logo(Long tenantId) {
        return ownLogo(tenantId).or(this::installationLogo);
    }

    /**
     * The look of a tenant's own login page /login/&lt;key&gt; - empty for a key nobody has (any more) or a deactivated
     * tenant: the page is then the neutral one.
     */
    public Optional<Branding> forLoginKey(String loginKey) {
        return activeTenantId(loginKey).map(this::forTenant);
    }

    /** The logo of a tenant's own login page, else the installation's. */
    public Optional<Logo> loginLogo(String loginKey) {
        return activeTenantId(loginKey).flatMap(this::logo);
    }

    /** The key in the address of the tenant's own login page; 404 for an unknown tenant. */
    public String loginKey(Long tenantId) {
        return requireTenant(tenantId).loginKey();
    }

    /** A new key for the tenant's login page - the old address shows the neutral page from now on. */
    @Transactional
    public String renewLoginKey(Long tenantId) {
        requireTenant(tenantId);
        String key = TenantService.newLoginKey();
        tenants.updateLoginKey(tenantId, key);
        return key;
    }

    /** What the admin set for the tenant (nothing = the defaults throughout); 404 for an unknown tenant. */
    public Optional<TenantBranding> findOwn(Long tenantId) {
        requireTenant(tenantId);
        return repository.findById(tenantId);
    }

    /** The logo the admin uploaded for the tenant, without the installation's. */
    public Optional<Logo> ownLogo(Long tenantId) {
        return repository.findById(tenantId)
                .filter(branding -> branding.logoType() != null)
                .flatMap(branding -> storage.open(logoKey(tenantId)).map(content -> new Logo(read(content), branding.logoType())));
    }

    /**
     * Sets the tenant's name and primary colour; null or blank = the installation's default. 400 for a name longer than
     * {@value #MAX_NAME} characters or a colour that is not "#rrggbb"; 404 for an unknown tenant.
     */
    @Transactional
    public TenantBranding save(Long tenantId, @Nullable String name, @Nullable String primaryColor) {
        requireTenant(tenantId);
        String brandName = blankToNull(name);
        if (brandName != null && brandName.length() > MAX_NAME) {
            throw ServiceException.badRequest("branding.nameTooLong", "The name may have at most " + MAX_NAME
                    + " characters",
                    Map.of("max", String.valueOf(MAX_NAME)));
        }
        String color = blankToNull(primaryColor);
        if (color != null && !COLOR.matcher(color).matches()) {
            throw ServiceException.badRequest("branding.colorInvalid", "The colour must be given as #rrggbb");
        }
        Optional<TenantBranding> existing = repository.findById(tenantId);
        return store(new TenantBranding(tenantId, brandName, color == null ? null : color.toLowerCase(),
                existing.map(TenantBranding::logoType).orElse(null), Instant.now()), existing.isPresent());
    }

    /**
     * Stores the tenant's logo, replacing one before: PNG, JPEG or WebP (recognised by content, never SVG - it can carry
     * scripts), at most {@value #MAX_LOGO_BYTES} bytes - 400 / 415 otherwise; 404 for an unknown tenant.
     */
    @Transactional
    public TenantBranding saveLogo(Long tenantId, byte[] content) {
        requireTenant(tenantId);
        String type = logoType(content);
        storage.put(logoKey(tenantId), content, type);
        Optional<TenantBranding> existing = repository.findById(tenantId);
        return store(new TenantBranding(tenantId, existing.map(TenantBranding::name).orElse(null),
                existing.map(TenantBranding::primaryColor).orElse(null), type, Instant.now()), existing.isPresent());
    }

    /** Removes the tenant's logo - the installation's shows again; false if it had none. */
    @Transactional
    public boolean deleteLogo(Long tenantId) {
        Optional<TenantBranding> existing = repository.findById(tenantId).filter(branding -> branding.logoType() != null);
        if (existing.isEmpty()) {
            return false;
        }
        TenantBranding branding = existing.get();
        repository.update(new TenantBranding(tenantId, branding.name(), branding.primaryColor(), null, Instant.now()));
        storage.delete(logoKey(tenantId));
        return true;
    }

    /** The installation's defaults - the admin's dialog shows them for the values left empty. */
    public String defaultName() {
        return defaultName;
    }

    public String defaultColor() {
        return defaultColor;
    }

    private TenantBranding store(TenantBranding branding, boolean exists) {
        return exists ? repository.update(branding) : repository.save(branding);
    }

    private Tenant requireTenant(Long tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> ServiceException.notFound("tenant.notFound", "Tenant not found"));
    }

    private Optional<Long> activeTenantId(String loginKey) {
        return tenants.findByLoginKey(loginKey).filter(Tenant::active).map(Tenant::id);
    }

    /** The logo's media type from its first bytes; 400 when empty or too large, 415 for anything else. */
    static String logoType(byte[] content) {
        if (content == null || content.length == 0) {
            throw ServiceException.badRequest("branding.logoEmpty", "The logo is empty");
        }
        if (content.length > MAX_LOGO_BYTES) {
            throw ServiceException.badRequest("branding.logoTooLarge", "The logo may have at most "
                    + MAX_LOGO_BYTES / 1024 + " KB",
                    Map.of("maxKb", String.valueOf(MAX_LOGO_BYTES / 1024)));
        }
        if (startsWith(content, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) {
            return "image/png";
        }
        if (startsWith(content, 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(content, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && startsWith(content, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        throw new ServiceException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "branding.logoType",
                "The logo must be a PNG, JPEG or WebP image");
    }

    private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
        return content.length >= offset + prefix.length
                && Arrays.equals(content, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static String logoKey(Long tenantId) {
        return TenantService.storagePrefix(tenantId) + "branding/logo";
    }

    private static String requireDefaultName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME) {
            throw new IllegalStateException("app.branding.name must have 1 to " + MAX_NAME + " characters");
        }
        return name.strip();
    }

    private static String requireDefaultColor(String color) {
        if (color == null || !COLOR.matcher(color.strip()).matches()) {
            throw new IllegalStateException("app.branding.primary-color must be given as #rrggbb");
        }
        return color.strip().toLowerCase();
    }

    /** A file path, or "classpath:..." for one in the jar (the Zirmo logo by default). */
    private static Logo readDefaultLogo(String location) {
        try {
            byte[] content = location.startsWith(CLASSPATH) ? readResource(location.substring(CLASSPATH.length()))
                    : Files.readAllBytes(Path.of(location));
            return new Logo(content, logoType(content));
        } catch (IOException | ServiceException e) {
            throw new IllegalStateException("app.branding.logo " + location + " is not a readable PNG, JPEG or WebP "
                    + "image of at most " + MAX_LOGO_BYTES / 1024 + " KB", e);
        }
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream content = BrandingService.class.getClassLoader().getResourceAsStream(name)) {
            if (content == null) {
                throw new IOException("No resource " + name);
            }
            return content.readAllBytes();
        }
    }

    private static byte[] read(InputStream content) {
        try (content) {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A logo and its media type. */
    public record Logo(byte[] content, String contentType) {
    }
}
