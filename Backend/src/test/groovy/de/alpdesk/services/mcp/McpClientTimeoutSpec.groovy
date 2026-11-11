package de.alpdesk.services.mcp

import de.alpdesk.services.common.ServiceException
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/** A software that does not answer in time (app.ai.timeout): the call fails with its own reason, naming the connection. */
@MicronautTest(startApplication = false)
@Property(name = "app.ai.timeout", value = "1s")
class McpClientTimeoutSpec extends Specification {

    FakeMcpSoftware software = new FakeMcpSoftware()

    @Inject
    McpClient client

    def cleanup() {
        software.stop()
    }

    def "a tool call the software does not answer in time fails as such"() {
        given:
        software.callDelayMillis = 3_000

        when:
        client.callTool(McpClientSpec.NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "1"])

        then:
        def e = thrown(ServiceException)
        e.code() == "mcpServer.timeout"
        e.message == "Buchhaltung did not answer in time"
    }
}
