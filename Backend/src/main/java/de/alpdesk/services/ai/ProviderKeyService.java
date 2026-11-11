package de.alpdesk.services.ai;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.KeySource;
import de.alpdesk.entities.ai.ProviderKey;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.ai.ProviderKeyRepository;
import de.alpdesk.security.SecretCipher;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The AI providers' API keys: each tenant's own and the admin's global ones, stored encrypted. A call uses the tenant's
 * own key for the provider; without one, the admin's global key if the tenant is allowed to (then the call is billed).
 */
@Singleton
public class ProviderKeyService {

    private static final int MIN_KEY_LENGTH = 10;
    private static final int HINT_LENGTH = 4;

    private final ProviderKeyRepository repository;
    private final SecretCipher cipher;

    public ProviderKeyService(ProviderKeyRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public List<ProviderKey> findOwn(Long tenantId) {
        return repository.findByTenantIdOrderByProvider(tenantId);
    }

    public List<ProviderKey> findGlobal() {
        return repository.findGlobal();
    }

    /** Stores or replaces the tenant's own key for the provider. */
    @Transactional
    public ProviderKey saveOwn(Long tenantId, AiProvider provider, String apiKey) {
        return save(repository.findByTenantIdAndProvider(tenantId, provider), tenantId, provider, apiKey);
    }

    /** Stores or replaces the admin's global key for the provider. */
    @Transactional
    public ProviderKey saveGlobal(AiProvider provider, String apiKey) {
        return save(repository.findGlobalByProvider(provider), null, provider, apiKey);
    }

    @Transactional
    public boolean deleteOwn(Long tenantId, AiProvider provider) {
        return repository.deleteByTenantIdAndProvider(tenantId, provider) > 0;
    }

    @Transactional
    public boolean deleteGlobal(AiProvider provider) {
        return repository.deleteGlobalByProvider(provider) > 0;
    }

    /**
     * The key a call of the tenant with the model would use - empty if it has none. A model on an own server brings its
     * own key (or needs none): the call counts as the admin's, billed at the model's price - which the admin sets to 0
     * for a server the tenant runs itself.
     */
    public Optional<ResolvedKey> find(Tenant tenant, AiModel model) {
        if (model.provider().hasOwnServer()) {
            return Optional.of(new ResolvedKey(model.apiKey() == null ? null : cipher.decrypt(model.apiKey()),
                    KeySource.GLOBAL));
        }
        Optional<ProviderKey> own = repository.findByTenantIdAndProvider(tenant.id(), model.provider());
        if (own.isPresent()) {
            return Optional.of(new ResolvedKey(cipher.decrypt(own.get().apiKey()), KeySource.OWN));
        }
        if (!tenant.globalKeysAllowed()) {
            return Optional.empty();
        }
        return repository.findGlobalByProvider(model.provider())
                .map(global -> new ResolvedKey(cipher.decrypt(global.apiKey()), KeySource.GLOBAL));
    }

    /** Whose key a call of the tenant with the model would use, without decrypting it - empty if it has none. */
    public Optional<KeySource> sourceOf(Tenant tenant, AiModel model) {
        if (model.provider().hasOwnServer()) {
            return Optional.of(KeySource.GLOBAL);
        }
        if (repository.findByTenantIdAndProvider(tenant.id(), model.provider()).isPresent()) {
            return Optional.of(KeySource.OWN);
        }
        return tenant.globalKeysAllowed() && repository.findGlobalByProvider(model.provider()).isPresent()
                ? Optional.of(KeySource.GLOBAL) : Optional.empty();
    }

    /** As {@link #find}, but 409 when the tenant has no key for the model's provider. */
    public ResolvedKey resolve(Tenant tenant, AiModel model) {
        return find(tenant, model).orElseThrow(() -> ServiceException.conflict("providerKey.missing", "No API key for "
                + model.provider().name() + " - a tenant admin adds one under API-Keys",
                Map.of("provider", model.provider().name())));
    }

    private ProviderKey save(Optional<ProviderKey> existing, Long tenantId, AiProvider provider, String apiKey) {
        if (provider == null) {
            throw ServiceException.badRequest("providerKey.unknownProvider", "Unknown provider");
        }
        if (provider.hasOwnServer()) {
            throw ServiceException.badRequest("providerKey.ownServerInCatalog",
                    "A model on an own server has its key in the model catalog");
        }
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.length() < MIN_KEY_LENGTH || key.chars().anyMatch(Character::isWhitespace)) {
            throw ServiceException.badRequest("key.notApiKey", "This is not an API key");
        }
        String hint = "…" + key.substring(key.length() - HINT_LENGTH);
        // As PostgreSQL keeps it (microseconds): the answer to the save then equals what a later read returns - Linux
        // clocks give nanoseconds, macOS ones microseconds.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (existing.isPresent()) {
            return repository.update(new ProviderKey(existing.get().id(), tenantId, provider, cipher.encrypt(key), hint,
                    existing.get().createdAt(), now));
        }
        return repository.save(new ProviderKey(null, tenantId, provider, cipher.encrypt(key), hint, now, now));
    }
}
