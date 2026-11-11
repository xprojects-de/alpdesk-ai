package de.alpdesk.services.oauth

import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

/**
 * The specs decide whether the installation's address counts as public - they never depend on the machine's DNS. By
 * default it is not (a company network); {@code publicAddress} plays the SaaS installation.
 */
@Singleton
@Replaces(OAuthReachability)
class FakeReachability extends OAuthReachability {

    volatile boolean publicAddress = false

    void reset() {
        publicAddress = false
    }

    @Override
    boolean isPublic(String host) {
        publicAddress
    }
}
