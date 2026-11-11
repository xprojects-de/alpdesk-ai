package de.alpdesk.dto.account;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record PasswordChangeRequest(
        String currentPassword,
        String newPassword
) {
}
