package de.alpdesk.dto.branding;

import io.micronaut.serde.annotation.Serdeable;

/** The key of the tenant's own login page: /login/&lt;loginKey&gt; - the frontend builds the address. */
@Serdeable
public record LoginKeyResponse(String loginKey) {
}
