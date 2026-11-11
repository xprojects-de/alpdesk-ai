package de.alpdesk.controller.common

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.common.ServiceException
import io.micronaut.data.exceptions.DataAccessException
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * Every error answer carries a stable code next to its English message, and the values inserted into the message as
 * params: the frontend translates the code, the client software may switch on it.
 */
@MicronautTest(transactional = false)
class ErrorCodeSpec extends Specification {

    static final String CODE = /^[a-z][a-zA-Z0-9]*\.[a-z][a-zA-Z0-9]*$/

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a refused input names its rule and the values of its message (400)"() {
        given:
        def tenant = api.tenant()

        when:
        def body = refused("POST", "/admin/tenants/${tenant.id}/users",
                [username: "user-" + AiApi.unique(), displayName: "Maria", password: "kurz"])

        then:
        body == [status : HttpStatus.BAD_REQUEST, message: "The password must have at least 12 characters",
                 code   : "user.passwordTooShort", params: [min: "12"]]
    }

    def "a sandbox token of the wrong length names both limits: #reason"() {
        given:
        def tenant = api.tenant()

        expect:
        refused("PUT", "/admin/tenants/${tenant.id}",
                [name: tenant.name, sandboxUrls: "http://sandbox-${AiApi.unique()}:8080".toString(), sandboxToken: token]) ==
                [status: HttpStatus.BAD_REQUEST, message: "Sandbox token must have 32 to 200 characters and no spaces",
                 code  : "sandbox.tokenInvalid", params: [min: "32", max: "200"]]

        where:
        reason      | token
        "too short" | "t" * 31
        "too long"  | "t" * 201
    }

    def "an unknown tenant is a 404 with its code and no params"() {
        expect:
        refused("GET", "/admin/tenants/999999999/provider-keys") ==
                [status: HttpStatus.NOT_FOUND, message: "Tenant not found", code: "tenant.notFound", params: [:]]
    }

    def "a username taken already is a 409 that names it"() {
        given:
        def tenant = api.tenant()
        def username = "user-" + AiApi.unique()
        api.admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "Maria", password: AiApi.USER_PASSWORD])

        when:
        def body = refused("POST", "/admin/tenants/${tenant.id}/users",
                [username: username, displayName: "Max", password: AiApi.USER_PASSWORD])

        then:
        body == [status: HttpStatus.CONFLICT, message: "The username \"" + username + "\" is already taken",
                 code  : "user.usernameTaken", params: [username: username]]
    }

    def "the user's own language of the wrong form is refused with its code"() {
        given:
        def token = api.userToken(api.tenant().id)

        expect:
        refused("PUT", "/tenant/account/language", [language: "nicht eine Sprache"], token).code == "user.languageInvalid"
    }

    def "a body that is no JSON is refused by the handler's own code"() {
        when:
        client.toBlocking().exchange(HttpRequest.POST("/admin/tenants", "{kaputt")
                .contentType(MediaType.APPLICATION_JSON_TYPE).bearerAuth(api.adminToken), Map)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
        e.response.getBody(Map).get() == [message: "The request body is not valid JSON.", code: "request.unreadable", params: [:]]
    }

    def "a wrong password at the login carries a code too"() {
        when:
        client.toBlocking().exchange(HttpRequest.POST("/login", [username: "niemand-" + AiApi.unique(), password: "falsch"]), Map)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
        e.response.getBody(Map).get().code == "auth.invalidCredentials"
    }

    def "the chat stream's error event carries the failure's code and params"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-key-of-ANTHROPIC"])
        fake.failures = 1

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Hallo"])

        then:
        events*.event == ["error"]
        events.last().data == [message: "ANTHROPIC refused the API key - check it under API-Keys",
                               code   : "provider.keyRefused", params: [name: "ANTHROPIC"]]
    }

    def "the handler gives a database failure (#state) its code"() {
        given:
        def handler = new GlobalExceptionHandler()
        def failure = new DataAccessException("failed", new RuntimeException(new SQLException("database says no", state)))

        when:
        def response = handler.handleDataAccessException(HttpRequest.PUT("/admin/tenants/1", ""), failure)

        then:
        response.status() == status
        response.body().code() == code
        response.body().params() == [:]

        where:
        state   || status                         | code
        "23505" || HttpStatus.CONFLICT            | "db.uniqueness"
        "23502" || HttpStatus.BAD_REQUEST         | "db.invalidData"
        "40P01" || HttpStatus.SERVICE_UNAVAILABLE | "db.changedMeanwhile"
        "08006" || HttpStatus.SERVICE_UNAVAILABLE | "db.unavailable"
        "XX000" || HttpStatus.INTERNAL_SERVER_ERROR | "error.unexpected"
    }

    def "no free database connection is the handler's db.busy"() {
        when:
        def response = new GlobalExceptionHandler().handleUnexpectedException(HttpRequest.GET("/tenant/account"),
                new RuntimeException(new SQLTransientConnectionException("pool exhausted")))

        then:
        response.status() == HttpStatus.SERVICE_UNAVAILABLE
        response.body().code() == "db.busy"
    }

    def "every factory keeps its code, status and params (#factory with #params)"() {
        when:
        ServiceException e = params ? ServiceException."$factory"("area.rule", "A message", params)
                : ServiceException."$factory"("area.rule", "A message")

        then:
        e.status() == status
        e.code() == "area.rule"
        e.message == "A message"
        e.params() == params

        where:
        factory       | params          || status
        "badRequest"  | [:]             || HttpStatus.BAD_REQUEST
        "badRequest"  | [max: "5"]      || HttpStatus.BAD_REQUEST
        "notFound"    | [:]             || HttpStatus.NOT_FOUND
        "notFound"    | [id: "7"]       || HttpStatus.NOT_FOUND
        "conflict"    | [:]             || HttpStatus.CONFLICT
        "conflict"    | [name: "Max"]   || HttpStatus.CONFLICT
        "unavailable" | [:]             || HttpStatus.SERVICE_UNAVAILABLE
        "unavailable" | [kind: "audio"] || HttpStatus.SERVICE_UNAVAILABLE
    }

    def "the constructor keeps any status with its code and params"() {
        expect:
        with(new ServiceException(HttpStatus.BAD_GATEWAY, "provider.failed", "X failed", [name: "X"])) {
            status() == HttpStatus.BAD_GATEWAY && code() == "provider.failed" && params() == [name: "X"]
        }
        new ServiceException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "media.notSupported", "No media").params() == [:]
    }

    /**
     * Reads the sources, not the running code: a code is a string literal at the throw site, and most sites are only
     * reached by a rare failure - a scan checks every one of them, not just those a spec happens to provoke. Sites that
     * pass a code on (a helper's parameter) are named; their callers' literals are checked through the helper's name.
     */
    def "every code thrown or answered has the form <area>.<rule>"() {
        given:
        Map<String, List<String>> forwarded = [
                "ServiceException.java": ["code"],
                "AiModels.java"        : ["code"],
                "McpClient.java"       : ["code"],
                "A2aClient.java"       : ["code"],
                "GraphStoreClient.java": ["code"],
                "OAuthHttp.java"       : ["code"],
                "McpAddresses.java"    : ["refusal.code()", "refusal.codeAtCall()"],
                "ErrorResponse.java"   : ["code"],
                "ChatEvents.java"      : ["failure.code()"],
                "GlobalExceptionHandler.java": ["exception.code()"]]
        List<String> invalid = []
        int checked = 0

        when:
        new File("src/main/java").eachFileRecurse { File file ->
            if (!file.name.endsWith(".java")) {
                return
            }
            String source = file.text
            codeArguments(source).each { String argument ->
                if (argument.startsWith('"')) {
                    checked++
                    if (!(argument[1..-2] ==~ CODE)) {
                        invalid << file.name + ": " + argument
                    }
                } else if (!(argument in forwarded.getOrDefault(file.name, []))) {
                    invalid << file.name + ": not a literal code - " + argument
                }
            }
        }

        then:
        invalid == []
        checked > 200
    }

    /**
     * Every code a throw site names has its text in the frontend (CLAUDE.md "services/"): without one a German user reads
     * the English message. The frontend's own spec (app/i18n/i18n.spec.ts) checks every language has English's keys.
     */
    def "every code thrown or answered is translated in the frontend"() {
        given:
        Map english = ObjectMapper.getDefault().readValue(new File("../Frontend/src/i18n/en.json").bytes, Map)
        Map backend = english.errors.backend as Map
        Set<String> untranslated = new TreeSet<>()

        when:
        new File("src/main/java").eachFileRecurse { File file ->
            if (file.name.endsWith(".java")) {
                codeArguments(file.text).findAll { it.startsWith('"') }.each { String argument ->
                    def (String area, String rule) = argument[1..-2].split("\\.")
                    if (!((backend[area] as Map)?.containsKey(rule))) {
                        untranslated << file.name + ": " + argument
                    }
                }
            }
        }

        then:
        untranslated.isEmpty()
    }

    /**
     * The code argument of every throw site, of the helpers that take one (AiModels', McpClient's gateway, Refusal) and of
     * every error answer built directly (ErrorResponse.of, new ErrorResponse - the handler's own codes).
     */
    private static List<String> codeArguments(String source) {
        List<String> codes = []
        def calls = ~/(ServiceException\.(?:badRequest|conflict|notFound|unavailable)|new ServiceException|\bgateway|new Refusal|ErrorResponse\.of|new ErrorResponse)\(/
        def matcher = calls.matcher(source)
        while (matcher.find()) {
            if (source.substring(0, matcher.start()).endsWith("ServiceException ")) {
                continue // the helper's own declaration
            }
            List<String> arguments = arguments(source, matcher.end())
            if (matcher.group(1) in ["new ServiceException", "new ErrorResponse"]) {
                codes << arguments[1]
            } else if (matcher.group(1) == "new Refusal") {
                codes.addAll(arguments[0..1])
            } else {
                codes << arguments[0]
            }
        }
        codes
    }

    /** The top-level arguments of the call whose parenthesis opens before {@code start}. */
    private static List<String> arguments(String source, int start) {
        List<String> arguments = []
        StringBuilder current = new StringBuilder()
        int depth = 1
        boolean inString = false
        for (int i = start; depth > 0; i++) {
            char c = source.charAt(i)
            if (inString) {
                if (c == '\\' as char) {
                    current.append(c)
                    c = source.charAt(++i)
                } else if (c == '"' as char) {
                    inString = false
                }
            } else if (c == '"' as char) {
                inString = true
            } else if (c == '(' as char) {
                depth++
            } else if (c == ')' as char && --depth == 0) {
                break
            } else if (c == ',' as char && depth == 1) {
                arguments << current.toString().trim()
                current = new StringBuilder()
                continue
            }
            current.append(c)
        }
        arguments << current.toString().trim()
        arguments
    }

    /** The refused call's status and body. */
    private Map refused(String method, String path, Map body = null, String token = api.adminToken) {
        try {
            api.call(token, method, path, body)
            throw new AssertionError("$method $path was not refused")
        } catch (HttpClientResponseException e) {
            [status: e.status] + e.response.getBody(Map).get()
        }
    }
}
