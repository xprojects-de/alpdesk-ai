package de.alpdesk.security

import io.micronaut.test.extensions.spock.annotation.MicronautTest

/** The scenarios of {@link LoginModesSpec} as installed by default: the password login, the header ignored. */
@MicronautTest(transactional = false)
class LoginWithoutWamSpec extends LoginModesSpec {

    @Override
    boolean wam() {
        false
    }
}
