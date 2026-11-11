package de.alpdesk.security

import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.spock.annotation.MicronautTest

/** The scenarios of {@link LoginModesSpec} behind a WAM proxy: tenant users through its header only. */
@MicronautTest(transactional = false)
@Property(name = "app.wam.enabled", value = "true")
class LoginWithWamSpec extends LoginModesSpec {

    @Override
    boolean wam() {
        true
    }
}
