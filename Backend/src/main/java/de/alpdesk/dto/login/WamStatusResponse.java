package de.alpdesk.dto.login;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Whether tenant users sign in through a WAM proxy - the login page then signs them in without asking -, and whether
 * a user group's WAM group is checked against the IDM groups the proxy sends.
 */
@Serdeable
public record WamStatusResponse(boolean enabled, boolean groupsChecked) {
}
