package de.alpdesk.dto.oauth;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** Starting a connection's sign-in: the frontend's page to come back to - absolute, on this installation's host. */
@Serdeable
public record SignInRequest(@Nullable String returnUrl) {
}
