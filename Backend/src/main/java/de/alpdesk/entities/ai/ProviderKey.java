package de.alpdesk.entities.ai;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("provider_key")
public record ProviderKey(

        @Id
        @GeneratedValue
        Long id,

        /** The tenant whose own key it is; null for one of the admin's global keys. */
        @Nullable
        Long tenantId,

        AiProvider provider,

        /** Encrypted (security.SecretCipher) - never sent out. */
        String apiKey,

        /** The key's last characters, to recognise it. */
        String keyHint,

        Instant createdAt,

        Instant updatedAt
) {
}
