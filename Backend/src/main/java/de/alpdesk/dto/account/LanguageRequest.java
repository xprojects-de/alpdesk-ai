package de.alpdesk.dto.account;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** The tenant user's own choice of its frontend's language, a language tag such as "en" or "de". */
@Serdeable
public record LanguageRequest(
        @Nullable
        String language
) {
}
