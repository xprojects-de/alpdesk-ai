package de.alpdesk.services.a2a

import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

/**
 * The specs' webhook server listens on 127.0.0.1 over plain HTTP - the one address let through here
 * (test.local-webhooks=true); every other address is checked as in production.
 */
@Singleton
@Replaces(PushAddresses)
@Requires(property = "test.local-webhooks", value = "true")
class LocalPushAddresses extends PushAddresses {

    @Override
    void validate(String url) {
        if (url.startsWith("http://127.0.0.1:")) {
            return
        }
        super.validate(url)
    }
}
