package de.alpdesk.controller.common

import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.services.common.ServiceException
import io.micronaut.core.convert.exceptions.ConversionErrorException
import io.micronaut.core.type.Argument
import io.micronaut.data.exceptions.DataAccessException
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.http.server.exceptions.NotFoundException
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.authentication.AuthorizationException
import io.micronaut.transaction.exceptions.CannotCreateTransactionException
import io.micronaut.web.router.exceptions.UnsatisfiedPartRouteException
import io.micronaut.web.router.exceptions.UnsatisfiedQueryValueRouteException
import spock.lang.Specification

import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * A request this API cannot bind is the caller's mistake, so it has to come back as a 400 naming
 * what is wrong - not as the generic 500 the Throwable catch-all would produce for it, which tells
 * the caller nothing and buries a real fault's stack trace under every typo's.
 */
class GlobalExceptionHandlerSpec extends Specification {

    def handler = new GlobalExceptionHandler()

    def "database messages reach the log without the failing row's values (personal data)"() {
        given:
        def psql = new RuntimeException("ERROR: null value in column \"file_name\" of relation \"document\" violates not-null constraint\n"
                + "  Detail: Failing row contains (1, 2, Max, Muster, max@example.org).")
        def wrapped = new DataAccessException("insert failed", psql)

        expect:
        GlobalExceptionHandler.rootCauseMessage(wrapped) ==
                'ERROR: null value in column "file_name" of relation "document" violates not-null constraint'
        !GlobalExceptionHandler.rootCauseMessage(wrapped).contains("Muster")
    }

    def "a connected software's or agent's error text reaches the caller but never the log - it may quote what a user typed: #code"() {
        given: "the software refused a prompt's value and quoted it"
        def refused = new ServiceException(HttpStatus.BAD_GATEWAY, code,
                "Buchhaltung answered with an error: Kunde 'Max Mustermann' unbekannt",
                [name: "Buchhaltung", error: "Kunde 'Max Mustermann' unbekannt"])
        def path = "/tenant/chat/prompts/${UUID.randomUUID()}"

        when:
        def response = handler.handleServiceException(HttpRequest.POST(path, ""), refused)

        then:
        response.status() == HttpStatus.BAD_GATEWAY
        response.body().params().error == "Kunde 'Max Mustermann' unbekannt"
        def lines = RecentLogAppender.installed().entries().findAll { it.message().contains(path) }*.message()
        lines == ["Service operation failed on POST ${path}: ${code}".toString()]

        where:
        code << ["mcpServer.error", "a2a.agentFailed"]
    }

    def "any other service failure keeps its message in the log"() {
        given:
        def failed = ServiceException.unavailable("ai.unavailable", "Modell X is not reachable")
        def path = "/tenant/chat/stream/${UUID.randomUUID()}"

        when:
        handler.handleServiceException(HttpRequest.POST(path, ""), failed)

        then:
        RecentLogAppender.installed().entries().findAll { it.message().contains(path) }*.message() ==
                ["Service operation failed on POST ${path}: Modell X is not reachable".toString()]
    }

    def "a deadlock, a serialization failure or a lock wait past lock_timeout is a 503 'try again', not a 500"() {
        given: "the SQL state deep in the cause chain, as the driver reports it"
        def wrapped = new DataAccessException("update failed", new RuntimeException(new SQLException("database says no", state)))

        when:
        def response = handler.handleDataAccessException(HttpRequest.PUT("/api/v1/documents/1", ""), wrapped)

        then:
        response.status() == status

        where:
        state   | status
        "40P01" | HttpStatus.SERVICE_UNAVAILABLE // deadlock detected
        "40001" | HttpStatus.SERVICE_UNAVAILABLE // serialization failure
        "55P03" | HttpStatus.SERVICE_UNAVAILABLE // lock not available (lock_timeout)
        "XX000" | HttpStatus.INTERNAL_SERVER_ERROR
    }

    def "a database out of reach or out of resources (#state) is a 503 'try again', not a 500"() {
        given:
        def wrapped = new DataAccessException("query failed", new RuntimeException(new SQLException("database says no", state)))

        when:
        def response = handler.handleDataAccessException(HttpRequest.PUT("/api/v1/documents", ""), wrapped)

        then:
        response.status() == HttpStatus.SERVICE_UNAVAILABLE
        response.body().message() == "The database is briefly unavailable - please try again."

        where:
        state << [
                "53200", // out of shared memory: the lock table is full (several large imports at once)
                "53300", // too many connections
                "08006", // connection failure
                "08003", // connection does not exist
                "57P01", // admin shutdown (PostgreSQL restarting)
                "57P03", // cannot connect now (PostgreSQL starting)
        ]
    }

    def "a lock wait or deadlock in a tenant database - its plain JDBC repositories wrap it - is a 503 too (#state)"() {
        given:
        def wrapped = new IllegalStateException("Could not search the texts", new SQLException("database says no", state))

        when:
        def response = handler.handleUnexpectedException(HttpRequest.GET("/tenant/chat/conversations"), wrapped)

        then:
        response.status() == HttpStatus.SERVICE_UNAVAILABLE
        response.body().message() == "The data is being changed by another request - please try again."

        where:
        state << ["55P03", "40P01", "40001"]
    }

    def "a connection lost during a commit, wrapped by the transaction manager, is a 503 as well"() {
        given:
        def wrapped = new CannotCreateTransactionException("commit failed", new SQLException("I/O error", "08006"))

        expect:
        handler.handleUnexpectedException(HttpRequest.PUT("/api/v1/documents/1", ""), wrapped).status() ==
                HttpStatus.SERVICE_UNAVAILABLE
    }

    def "a constraint violation is judged by its SQL state (#state), whatever language the database answers in"() {
        given: "a PostgreSQL running with German messages"
        def wrapped = new DataAccessException("insert failed", new SQLException(message, state))

        when:
        def response = handler.handleDataAccessException(HttpRequest.PUT("/api/v1/documents/1", ""), wrapped)

        then:
        response.status() == status

        where:
        state   | message                                                                      | status
        "23505" | 'doppelter Schlüsselwert verletzt Unique-Constraint »document_origin_external_id_uq«' | HttpStatus.CONFLICT
        "23503" | 'Einfügen oder Aktualisieren verletzt Fremdschlüssel-Constraint »x_fk«'       | HttpStatus.BAD_REQUEST
        "23502" | 'NULL-Wert in Spalte »name« verletzt Not-Null-Constraint'                     | HttpStatus.BAD_REQUEST
        "23514" | 'neue Zeile verletzt Check-Constraint »document_size_check«'                  | HttpStatus.BAD_REQUEST
    }

    def "no free database connection within the pool's timeout is a 503 'try again', not a 500"() {
        given: "Hikari's timeout, wrapped by the transaction manager (not a DataAccessException)"
        def timeout = new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 30000ms.")
        def wrapped = new CannotCreateTransactionException("Could not open JDBC connection for transaction", timeout)

        when:
        def response = handler.handleUnexpectedException(HttpRequest.PUT("/api/v1/documents/1", ""), wrapped)

        then:
        response.status() == HttpStatus.SERVICE_UNAVAILABLE
        response.body().message() == "The server is busy - please try again."
    }

    def "the same timeout inside a DataAccessException is a 503 as well"() {
        given:
        def wrapped = new DataAccessException("query failed", new SQLTransientConnectionException("request timed out"))

        when:
        def response = handler.handleDataAccessException(HttpRequest.GET("/api/v1/me"), wrapped)

        then: "the same reason as the other way"
        response.status() == HttpStatus.SERVICE_UNAVAILABLE
        response.body().message() == "The server is busy - please try again."
    }

    def "a data error without an SQL state is our 500 - the handler itself does not fail on it"() {
        given: "e.g. Micronaut Data reading NULL into a field not marked @Nullable"
        def wrapped = new DataAccessException("Null value read for non-null property")

        when:
        def response = handler.handleDataAccessException(HttpRequest.GET("/api/v1/me"), wrapped)

        then:
        response.status() == HttpStatus.INTERNAL_SERVER_ERROR
        response.body().message() == "An unexpected error occurred."
    }

    def "any other unexpected exception stays a 500"() {
        expect:
        handler.handleUnexpectedException(HttpRequest.GET("/api/v1/me"), new IllegalStateException("bug")).status() ==
                HttpStatus.INTERNAL_SERVER_ERROR
    }

    def "a missing required multipart part is a 400 that names it"() {
        given: "a multipart upload posted without its file part"
        def exception = new UnsatisfiedPartRouteException("file", Argument.of(String, "file"))

        when:
        def response = handler.handleUnsatisfiedRoute(HttpRequest.POST("/tenant/admin/documents", ""), exception)

        then:
        response.status() == HttpStatus.BAD_REQUEST
        response.body().message() == "Required parameter 'file' is missing."
        response.body().code() == "request.parameterMissing"
        response.body().params() == [name: "file"]
    }

    def "the same holds for every other unbindable parameter kind, not just parts"() {
        given: "one handler covers the whole UnsatisfiedRouteException family"
        def exception = new UnsatisfiedQueryValueRouteException("tenantId", Argument.of(Long, "tenantId"))

        when:
        def response = handler.handleUnsatisfiedRoute(HttpRequest.GET("/admin/tenants"), exception)

        then:
        response.status() == HttpStatus.BAD_REQUEST
        response.body().message() == "Required parameter 'tenantId' is missing."
        response.body().code() == "request.parameterMissing"
        response.body().params() == [name: "tenantId"]
    }

    def "a parameter that does not parse into its declared type is a 400 that names it"() {
        given:
        def exception = new ConversionErrorException(Argument.of(Long, "id"), new IllegalArgumentException("abc"))

        when:
        def response = handler.handleConversionError(HttpRequest.GET("/admin/tenants/abc"), exception)

        then:
        response.status() == HttpStatus.BAD_REQUEST
        response.body().message() == "Parameter 'id' has an invalid value."
        response.body().code() == "request.parameterInvalid"
        response.body().params() == [name: "id"]
    }

    def "a malformed request body is a 400 that does not echo the body back"() {
        when: "the parser's own message quotes the rejected input, so it is never read here"
        def response = handler.handleJsonSyntax(HttpRequest.POST("/admin/tenants", ""))

        then:
        response.status() == HttpStatus.BAD_REQUEST
        response.body().message() == "The request body is not valid JSON."
        response.body().code() == "request.unreadable"
    }

    def "a request for a path nothing serves stays a 404 instead of becoming a 500"() {
        when: "e.g. a stale chunk requested by a tab still running the previous frontend build"
        def response = handler.handleHttpStatusException(HttpRequest.GET("/chunk-old.js"), new NotFoundException())

        then:
        response.status() == HttpStatus.NOT_FOUND
        response.body().message() == "Not found."
        response.body().code() == "request.notFound"
    }

    def "the handler's own answers carry their code too - the frontend translates by it: #code"() {
        when:
        def response = answer(handler)

        then:
        response.status() == status
        response.body().code() == code
        response.body().params() == params

        where:
        code                | status                         | params          | answer
        "auth.required"     | HttpStatus.UNAUTHORIZED        | [:]             | { GlobalExceptionHandler h -> h.handleAuthorizationException(HttpRequest.GET("/admin/tenants"), new AuthorizationException(null)) }
        "auth.forbidden"    | HttpStatus.FORBIDDEN           | [:]             | { GlobalExceptionHandler h -> h.handleAuthorizationException(HttpRequest.GET("/admin/tenants"), new AuthorizationException(Authentication.build("anna"))) }
        "request.refused"   | HttpStatus.METHOD_NOT_ALLOWED  | [status: "405"] | { GlobalExceptionHandler h -> h.handleHttpStatusException(HttpRequest.PATCH("/admin/tenants", ""), new HttpStatusException(HttpStatus.METHOD_NOT_ALLOWED, "no")) }
        "request.tooLarge"  | HttpStatus.REQUEST_ENTITY_TOO_LARGE | [:]        | { GlobalExceptionHandler h -> h.handleContentLengthExceeded(HttpRequest.POST("/tenant/admin/documents", "")) }
        "request.uriInvalid"| HttpStatus.BAD_REQUEST         | [:]             | { GlobalExceptionHandler h -> h.handleInvalidUri(HttpRequest.GET("/"), new URISyntaxException("//", "bad")) }
        "error.unexpected"  | HttpStatus.INTERNAL_SERVER_ERROR | [:]           | { GlobalExceptionHandler h -> h.handleUnexpectedException(HttpRequest.GET("/api/v1/me"), new IllegalStateException("bug")) }
    }
}
