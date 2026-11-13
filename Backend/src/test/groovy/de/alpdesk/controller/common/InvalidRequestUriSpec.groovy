package de.alpdesk.controller.common

import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * Scanners on the internet send request targets Java's URI parser refuses, e.g. {@code GET //} (seen on the first
 * production server, October 2026). Caddy passes them through, and Micronaut hands the URISyntaxException to the error
 * routes - internet noise, so a 400 logged at DEBUG (like a scanner's 404), not a 500 with an ERROR stack trace.
 * The raw socket is needed: an HTTP client normalises such a target itself.
 */
@MicronautTest
class InvalidRequestUriSpec extends Specification {

    @Inject
    EmbeddedServer server

    def "GET // is no 500 - it answers as / does"() {
        // Micronaut before 5.2.6 refused the target (400); since 5.2.6 it routes it as "/". Either is fine - what must
        // not come back is the 500 with an ERROR stack trace.
        when:
        def statusLine = send("GET // HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")

        then:
        !statusLine.startsWith("HTTP/1.1 5")
        statusLine == send("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
    }

    private String send(String request) {
        def socket = new Socket(InetAddress.getLoopbackAddress(), server.port)
        try {
            socket.soTimeout = 10_000
            socket.outputStream.write(request.getBytes(StandardCharsets.US_ASCII))
            socket.outputStream.flush()
            return new BufferedReader(new InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII)).readLine()
        } finally {
            socket.close()
        }
    }
}
