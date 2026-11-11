package de.alpdesk.security

import io.micronaut.scheduling.TaskExecutors
import io.micronaut.security.authentication.provider.ExecutorAuthenticationProvider
import spock.lang.Specification

/** The password login runs on the blocking executor - its lookup and PBKDF2 never hold up the event loop. */
class AuthenticationProviderSpec extends Specification {

    def "the password login runs on the blocking executor"() {
        given:
        def provider = new AuthenticationProviderUserPassword("alpdesk", "alpdesk", null)

        expect:
        provider instanceof ExecutorAuthenticationProvider
        (provider as ExecutorAuthenticationProvider).executorName == TaskExecutors.BLOCKING
    }
}
