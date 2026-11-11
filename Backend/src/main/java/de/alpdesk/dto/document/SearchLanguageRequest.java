package de.alpdesk.dto.document;

import de.alpdesk.entities.tenant.SearchLanguage;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SearchLanguageRequest(@Nullable SearchLanguage language) {
}
