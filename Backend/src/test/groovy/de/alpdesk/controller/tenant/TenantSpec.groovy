package de.alpdesk.controller.tenant

import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.sandbox.FakeSandboxes
import de.alpdesk.services.sandbox.Sandboxes
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The admin's tenants: listed, read, created, changed and deleted; a name is unique (case-insensitive), switches left
 * out on a change stay as they are, an unknown id answers 404. The tenant's own sandbox: its addresses and token come
 * together, the token is stored encrypted and never sent out, and the admin checks its instances.
 */
@MicronautTest(transactional = false)
class TenantSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    TenantRepository tenants

    @Inject
    FakeSandboxes sandboxes

    @Shared
    AiApi api

    static final String TOKEN = "t" * 36 + "wxyz"

    def setup() {
        api = api ?: new AiApi(client)
        sandboxes.reset()
    }

    def "the admin creates, reads, lists, changes and deletes a tenant"() {
        when:
        def created = api.admin("POST", "/admin/tenants", [name: unique("Firma"), contactEmail: "it@firma.example",
                                                           globalKeysAllowed: true]) as Map

        then: "active unless said otherwise, with a database of its own, no code execution without a sandbox"
        created.active
        created.globalKeysAllowed
        !created.codeExecution
        created.databaseName ==~ /tenant_[a-z0-9]{16}/
        (api.admin("GET", "/admin/tenants/${created.id}") as Map).name == created.name
        (api.admin("GET", "/admin/tenants") as List<Map>).any { it.id == created.id }

        when: "changed with the switches left out"
        def changed = api.admin("PUT", "/admin/tenants/${created.id}", [name: created.name + " GmbH", notes: "Notiz"]) as Map

        then: "they stay as they were"
        changed.name == created.name + " GmbH"
        changed.notes == "Notiz"
        changed.active
        changed.globalKeysAllowed
        changed.databaseName == created.databaseName

        when:
        def switched = api.admin("PUT", "/admin/tenants/${created.id}", [name: changed.name, active: false,
                                                                          globalKeysAllowed: false]) as Map

        then:
        !switched.active
        !switched.globalKeysAllowed

        expect: "deleted, it is gone"
        api.status(api.adminToken, "DELETE", "/admin/tenants/${created.id}") == HttpStatus.NO_CONTENT
        api.status(api.adminToken, "GET", "/admin/tenants/${created.id}") == HttpStatus.NOT_FOUND
    }

    def "a tenant needs a name: #reason"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/tenants", body) == HttpStatus.BAD_REQUEST

        where:
        reason       | body
        "none"       | [active: true]
        "only blanks" | [name: "   "]
    }

    def "a name is unique, whatever its case - also when renaming; a tenant keeps its own name"() {
        given:
        def first = api.admin("POST", "/admin/tenants", [name: unique("Firma")]) as Map
        def second = api.admin("POST", "/admin/tenants", [name: unique("Firma")]) as Map

        expect:
        api.status(api.adminToken, "POST", "/admin/tenants", [name: first.name.toUpperCase()]) == HttpStatus.CONFLICT
        api.status(api.adminToken, "PUT", "/admin/tenants/${second.id}", [name: first.name.toLowerCase()]) == HttpStatus.CONFLICT
        api.status(api.adminToken, "PUT", "/admin/tenants/${first.id}", [name: first.name, notes: "x"]) == HttpStatus.OK
    }

    def "an unknown tenant answers 404: #method"() {
        expect:
        api.status(api.adminToken, method, "/admin/tenants/999999", body) == HttpStatus.NOT_FOUND

        where:
        method   | body
        "GET"    | null
        "PUT"    | [name: "Niemand"]
        "DELETE" | null
    }

    def "the tenant's sandbox: entered with its token, kept when left out, removed with its token"() {
        given:
        def muster = host("muster")

        when:
        def created = api.admin("POST", "/admin/tenants", [name: unique("Firma"),
                sandboxUrls: " http://${muster}:8080/ ,https://${muster}.example.com/muster, http://${muster}:8080".toString(),
                sandboxToken: " " + TOKEN + " "]) as Map

        then: "normalised, on, the token only as its hint - stored encrypted"
        created.codeExecution
        created.sandboxUrls == "http://${muster}:8080,https://${muster}.example.com/muster"
        created.sandboxTokenHint == "…wxyz"
        !created.containsKey("sandboxToken")
        tenants.findById(created.id as Long).get().sandboxToken().startsWith("enc:v1:")
        (api.admin("GET", "/admin/tenants") as List<Map>).every { !it.containsKey("sandboxToken") }

        when: "changed with both left out"
        def kept = api.admin("PUT", "/admin/tenants/${created.id}", [name: created.name]) as Map

        then:
        kept.codeExecution
        kept.sandboxUrls == created.sandboxUrls
        kept.sandboxTokenHint == "…wxyz"

        when: "other addresses, the token left as it is"
        def neu = "http://${host("neu")}:8080".toString()
        def moved = api.admin("PUT", "/admin/tenants/${created.id}", [name: created.name, sandboxUrls: neu,
                                                                       sandboxToken: ""]) as Map

        then:
        moved.sandboxUrls == neu
        moved.sandboxTokenHint == "…wxyz"

        when: "the addresses emptied"
        def removed = api.admin("PUT", "/admin/tenants/${created.id}", [name: created.name, sandboxUrls: " "]) as Map

        then: "the token goes with them"
        !removed.codeExecution
        removed.sandboxUrls == null
        removed.sandboxTokenHint == null
        tenants.findById(created.id as Long).get().sandboxToken() == null
    }

    def "a sandbox that is not valid is refused: #reason"() {
        given:
        def tenant = api.admin("POST", "/admin/tenants", [name: unique("Firma")]) as Map

        expect:
        api.status(api.adminToken, "PUT", "/admin/tenants/${tenant.id}", [name: tenant.name] + body) == HttpStatus.BAD_REQUEST
        api.status(api.adminToken, "POST", "/admin/tenants", [name: unique("Firma")] + body) == HttpStatus.BAD_REQUEST

        where:
        reason                      | body
        "not http"                  | [sandboxUrls: "ftp://sandbox:8080", sandboxToken: TOKEN]
        "no host"                   | [sandboxUrls: "http:///work", sandboxToken: TOKEN]
        "credentials in it"         | [sandboxUrls: "http://user:pass@sandbox:8080", sandboxToken: TOKEN]
        "a query"                   | [sandboxUrls: "http://sandbox:8080?x=1", sandboxToken: TOKEN]
        "a fragment"                | [sandboxUrls: "http://sandbox:8080#x", sandboxToken: TOKEN]
        "not an address"            | [sandboxUrls: "http://sand box:8080", sandboxToken: TOKEN]
        "only commas"               | [sandboxUrls: ", ,", sandboxToken: TOKEN]
        "a short token"             | [sandboxUrls: "http://sandbox:8080", sandboxToken: "t" * 31]
        "a token with a space"      | [sandboxUrls: "http://sandbox:8080", sandboxToken: "t" * 20 + " " + "t" * 20]
        "a token too long"          | [sandboxUrls: "http://sandbox:8080", sandboxToken: "t" * 201]
        "addresses without a token" | [sandboxUrls: "http://sandbox:8080"]
        "a token without addresses" | [sandboxToken: TOKEN]
        "a token, addresses emptied" | [sandboxUrls: "", sandboxToken: TOKEN]
    }

    def "an instance another tenant already has is refused - the container is what keeps tenants apart"() {
        given:
        def erste = host("erste")
        def erste2 = host("erste-2")
        def first = api.admin("POST", "/admin/tenants", [name: unique("Firma"),
                sandboxUrls: "http://${erste}:8080,http://${erste2}:8080".toString(), sandboxToken: TOKEN]) as Map
        def second = api.admin("POST", "/admin/tenants", [name: unique("Firma")]) as Map

        expect: "on create and on change, however the address is spelled"
        api.status(api.adminToken, "POST", "/admin/tenants", [name: unique("Firma"),
                sandboxUrls: "http://${erste2.toUpperCase()}:8080/".toString(), sandboxToken: TOKEN]) == HttpStatus.CONFLICT
        api.status(api.adminToken, "PUT", "/admin/tenants/${second.id}", [name: second.name,
                sandboxUrls: "http://${host("neu")}:8080, http://${erste}:8080".toString(), sandboxToken: TOKEN]) == HttpStatus.CONFLICT

        and: "the tenant keeps its own addresses on a change"
        (api.admin("PUT", "/admin/tenants/${first.id}", [name: first.name,
                sandboxUrls: "http://${erste}:8080".toString()]) as Map).sandboxUrls == "http://${erste}:8080"
    }

    def "the admin checks every instance of the tenant's sandbox with its token"() {
        given:
        def a = "http://${host("a")}:8080".toString()
        def b = "http://${host("b")}:8080".toString()
        def c = "http://${host("c")}:8080".toString()
        def d = "http://${host("d")}:8080".toString()
        def tenant = api.admin("POST", "/admin/tenants", [name: unique("Firma"),
                sandboxUrls: [a, b, c, d].join(","), sandboxToken: TOKEN]) as Map
        sandboxes.up = true
        sandboxes.statuses[b] = Sandboxes.Status.TOKEN_REFUSED
        sandboxes.statuses[c] = Sandboxes.Status.BUSY
        sandboxes.statuses[d] = Sandboxes.Status.UNUSABLE

        when:
        def checked = api.admin("GET", "/admin/tenants/${tenant.id}/sandbox") as Map

        then: "each with what its /health says - here the video image"
        checked.instances.collect { [url: it.url, status: it.status] } ==
                [[url: a, status: "reachable"], [url: b, status: "token-refused"], [url: c, status: "busy"],
                 [url: d, status: "unusable"]]
        checked.instances*.video == [true] * 4
        sandboxes.checkedTokens == [TOKEN] * 4

        when: "the sandbox without video"
        sandboxes.video = false

        then:
        (api.admin("GET", "/admin/tenants/${tenant.id}/sandbox") as Map).instances*.video == [false] * 4

        when: "none answers"
        sandboxes.up = false
        sandboxes.statuses.clear()

        then: "and of none it is known whether it examines videos"
        (api.admin("GET", "/admin/tenants/${tenant.id}/sandbox") as Map).instances*.status == ["unreachable"] * 4
        (api.admin("GET", "/admin/tenants/${tenant.id}/sandbox") as Map).instances*.video == [null] * 4
    }

    def "the admin's check says which instances carry a README of their own programs, and whether they agree"() {
        given:
        def a = "http://${host("a")}:8080".toString()
        def b = "http://${host("b")}:8080".toString()
        def tenant = api.admin("POST", "/admin/tenants", [name: unique("Firma"),
                sandboxUrls: [a, b].join(","), sandboxToken: TOKEN]) as Map
        sandboxes.up = true

        expect: "ours, without one"
        check(tenant).instances*.extras == [false, false]
        check(tenant).extrasAgree

        when: "both built on ours with the same README"
        sandboxes.extras = "simtest runs the company's test scripts"

        then:
        check(tenant).instances*.extras == [true, true]
        check(tenant).extrasAgree

        when: "one of them without it - a session may land there"
        sandboxes.extrasOf[b] = null

        then: "the check warns: the model gets none"
        check(tenant).instances*.extras == [true, false]
        !check(tenant).extrasAgree

        when: "none answers"
        sandboxes.up = false

        then:
        check(tenant).instances*.extras == [null, null]
        check(tenant).extrasAgree
    }

    def "the sandbox is the admin's: a tenant admin has no check of it"() {
        given:
        def tenant = api.admin("POST", "/admin/tenants", [name: unique("Firma"),
                sandboxUrls: "http://${host("a")}:8080".toString(), sandboxToken: TOKEN]) as Map
        def token = api.tenantAdminToken(tenant.id)

        expect: "the admin's endpoint refuses it, and there is none of its own (a route that does not exist: 403)"
        api.status(token, "GET", "/admin/tenants/${tenant.id}/sandbox") == HttpStatus.FORBIDDEN
        api.status(token, "GET", "/tenant/admin/sandbox") == HttpStatus.FORBIDDEN
        sandboxes.checkedTokens.isEmpty()
    }

    def "a tenant without a sandbox has nothing to check, an unknown one answers 404"() {
        given:
        def tenant = api.admin("POST", "/admin/tenants", [name: unique("Firma")]) as Map

        expect:
        (api.admin("GET", "/admin/tenants/${tenant.id}/sandbox") as Map).instances == []
        api.status(api.adminToken, "GET", "/admin/tenants/999999/sandbox") == HttpStatus.NOT_FOUND
        sandboxes.checkedTokens.isEmpty()
    }

    private Map check(Map tenant) {
        api.admin("GET", "/admin/tenants/${tenant.id}/sandbox") as Map
    }

    private static String unique(String prefix) {
        prefix + " " + UUID.randomUUID().toString().substring(0, 8)
    }

    /** A sandbox host of its own for every tenant - an address entered at two tenants is refused. */
    private static String host(String name) {
        "alpdesk-ai-sandbox-${name}-${UUID.randomUUID().toString().substring(0, 8)}"
    }
}
