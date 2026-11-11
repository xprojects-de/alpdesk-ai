package de.alpdesk.dto.oauth;

import io.micronaut.serde.annotation.Serdeable;

/** The server's sign-in page the browser goes to. */
@Serdeable
public record SignInResponse(String authorizationUrl) {
}
