package de.alpdesk.controller.document

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * A ZIP archive of documents imported into the knowledge base: every file under its path with its folder; imported
 * again, changed files are replaced, unchanged ones left, new ones added and missing ones kept; the same content under
 * another path is stored once; what cannot be read is skipped and reported.
 */
@MicronautTest(transactional = false)
class DocumentArchiveSpec extends Specification {

    static final byte[] TECHNIQUE = ("Grundlagen der Technik. Die Kurveneinleitung beginnt mit dem Umkanten. " * 5).getBytes("UTF-8")
    static final byte[] PLANNING = ("Trainingsplanung. Ein Mikrozyklus dauert eine Woche mit drei Einheiten. " * 5).getBytes("UTF-8")

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "an archive becomes documents with their folders; the chat names them by their path"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant)

        when:
        def files = api.importArchive(token, [
                "Handbuch/Kapitel/technik.txt": TECHNIQUE,
                "planung.txt"                  : PLANNING,
                "Handbuch/Kopie/technik.txt"   : TECHNIQUE,
                ".DS_Store"                    : "x".bytes,
                "__MACOSX/Handbuch/._technik"  : "x".bytes,
                "../ausserhalb.txt"            : PLANNING,
                "Handbuch/programm.exe"        : [0x4d, 0x5a, 0x90, 0x00] as byte[],
                "leer.txt"                     : new byte[0]]) as List<Map>
        def byPath = files.collectEntries { [(it.path): it] }

        then: "in the archive's order, each with what happened"
        files*.path == ["Handbuch/Kapitel/technik.txt", "planung.txt", "Handbuch/Kopie/technik.txt", ".DS_Store",
                        "__MACOSX/Handbuch/._technik", "../ausserhalb.txt", "Handbuch/programm.exe", "leer.txt"]
        byPath["Handbuch/Kapitel/technik.txt"].outcome == "CREATED"
        byPath["planung.txt"].outcome == "CREATED"
        byPath["Handbuch/Kopie/technik.txt"].outcome == "DUPLICATE"
        byPath["Handbuch/Kopie/technik.txt"].detail == "Handbuch/Kapitel/technik.txt"
        byPath[".DS_Store"].detail == "hidden file"
        byPath["__MACOSX/Handbuch/._technik"].detail == "hidden file"
        byPath["../ausserhalb.txt"].detail == "invalid path"
        byPath["Handbuch/programm.exe"].outcome == "SKIPPED"
        byPath["Handbuch/programm.exe"].detail.contains("not supported")
        byPath["leer.txt"].detail == "The document is empty"

        and: "two documents, the first with its folder"
        def documents = (api.call(token, "GET", "/tenant/admin/documents") as List<Map>).sort { it.fileName }
        documents*.fileName == ["planung.txt", "technik.txt"]
        documents*.folder == ["", "Handbuch/Kapitel"]
        documents.every { api.awaitDocument(token, it.id).status == "READY" }

        when:
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(token, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        def answer = api.ask(token, [modelId: chat.id, message: "Womit beginnt die Kurveneinleitung?"])

        then: "the chunk's header and the source carry the path"
        answer.sources.contains("Handbuch/Kapitel/technik.txt")
    }

    def "imported again: changed files are replaced, unchanged left, new ones added, missing ones kept"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant)
        def first = api.importArchive(token, ["Handbuch/technik.txt": TECHNIQUE, "planung.txt": PLANNING,
                                              "alt.txt": "Ein alter Text, der bleiben soll. ".multiply(5).bytes]) as List<Map>
        first.each { api.awaitDocument(token, it.documentId) }
        int embeddingsBefore = fake.embeddings.size()

        when:
        def second = api.importArchive(token, [
                "Handbuch/technik.txt": (new String(TECHNIQUE, "UTF-8") + " Neu: der Stockeinsatz.").getBytes("UTF-8"),
                "planung.txt"         : PLANNING,
                "neu/kraft.txt"       : "Krafttraining im Sommer mit drei Einheiten pro Woche. ".multiply(5).bytes]) as List<Map>
        def byPath = second.collectEntries { [(it.path): it] }

        then:
        byPath["Handbuch/technik.txt"].outcome == "REPLACED"
        byPath["Handbuch/technik.txt"].documentId == first.find { it.path == "Handbuch/technik.txt" }.documentId
        byPath["planung.txt"].outcome == "UNCHANGED"
        byPath["planung.txt"].documentId == first.find { it.path == "planung.txt" }.documentId
        byPath["neu/kraft.txt"].outcome == "CREATED"

        and: "the missing one stays, nothing is there twice"
        def documents = api.call(token, "GET", "/tenant/admin/documents") as List<Map>
        documents.collect { it.folder ? it.folder + "/" + it.fileName : it.fileName }.sort() ==
                ["Handbuch/technik.txt", "alt.txt", "neu/kraft.txt", "planung.txt"]

        and: "only what changed or is new is embedded again"
        documents.each { api.awaitDocument(token, it.id) }
        fake.embeddings.size() - embeddingsBefore == 2
    }

    def "imported again with the content of another file, a known path is replaced, not taken for a duplicate"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant)
        def first = api.importArchive(token, ["technik.txt": TECHNIQUE, "planung.txt": PLANNING]) as List<Map>
        first.each { api.awaitDocument(token, it.documentId) }
        def technique = first.find { it.path == "technik.txt" }.documentId
        int embeddingsBefore = fake.embeddings.size()

        when:
        def second = api.importArchive(token, ["technik.txt": PLANNING]) as List<Map>

        then: "its old text is gone, the new one embedded"
        second*.outcome == ["REPLACED"]
        second[0].documentId == technique
        api.awaitDocument(token, technique).status == "READY"
        fake.embeddings.size() - embeddingsBefore == 1
    }

    def "the admin imports an archive for a tenant; another tenant never sees it"() {
        given:
        def tenant = api.tenant()
        adminWithModels(tenant)
        def other = api.tenant()
        def otherToken = adminWithModels(other)

        when:
        def files = api.importArchive(api.adminToken, ["handbuch.txt": PLANNING],
                "/admin/tenants/${tenant.id}/documents/archive") as List<Map>

        then:
        files*.outcome == ["CREATED"]
        (api.admin("GET", "/admin/tenants/${tenant.id}/documents") as List)*.fileName == ["handbuch.txt"]
        api.call(otherToken, "GET", "/tenant/admin/documents") == []
        api.importArchive(api.adminToken, ["a.txt": PLANNING], "/admin/tenants/999999/documents/archive") == HttpStatus.NOT_FOUND
    }

    def "a broken archive is refused, and so is one without an embedding model"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant)
        def withoutModel = api.tenantAdminToken(api.tenant().id)

        expect:
        api.importArchiveBytes(token, "kein zip".bytes) == HttpStatus.BAD_REQUEST
        api.importArchive(withoutModel, ["a.txt": PLANNING]) == HttpStatus.CONFLICT
    }

    def "an archive larger than a document may be is taken; a file in it beyond 50 MB is skipped"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant)
        def large = new byte[50 * 1024 * 1024 + 1]
        new Random(7).nextBytes(large)

        when: "the archive is about 50 MB itself - random bytes do not compress"
        def files = api.importArchive(token, ["gross.txt": large, "klein.txt": PLANNING]) as List<Map>

        then:
        files.find { it.path == "gross.txt" }.detail == "larger than 50 MB"
        files.find { it.path == "klein.txt" }.outcome == "CREATED"

        and: "a single document keeps its 50 MB"
        api.uploadFile(token, "gross.txt", large) == HttpStatus.BAD_REQUEST
    }

    /** A tenant admin with an embedding model and the tenant's own OpenAI key: its token. */
    private String adminWithModels(Map tenant) {
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        token
    }
}
