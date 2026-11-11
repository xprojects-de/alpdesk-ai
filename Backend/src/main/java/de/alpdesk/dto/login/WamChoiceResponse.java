package de.alpdesk.dto.login;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** No token yet: the person has a user in several tenants and chooses one (POST /login/wam with its id). */
@Serdeable
public record WamChoiceResponse(List<TenantChoice> tenants) {
}
