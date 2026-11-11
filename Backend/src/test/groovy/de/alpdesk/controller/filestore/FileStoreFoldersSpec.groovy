package de.alpdesk.controller.filestore

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.document.SourceApi
import de.alpdesk.services.task.FakeStoreClient
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * Choosing a folder of a store (docs/architecture/file-stores.md, knowledge-sources.md "API and frontend"): one level
 * of subfolders, sorted, without files and hidden ones - only of a ready store, never above
 * its folder. Through FakeStoreClient.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
class FileStoreFoldersSpec extends Specification {

    static final String STORES = "/tenant/admin/file-stores"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeStoreClient fake

    @Shared
    AiApi api

    SourceApi sources

    def setup() {
        api = api ?: new AiApi(client)
        sources = new SourceApi(api, fake)
        fake.reset()
    }

    def "a folder's subfolders, sorted - no files, no hidden ones; a subfolder's own"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/Projekte/2025/angebot.txt", "x")
        sources.write("${base}/Projekte/2026/angebot.txt", "x")
        sources.write("${base}/Handbuch/werkstatt.md", "x")
        sources.write("${base}/liesmich.txt", "x")
        sources.write("${base}/.versteckt/a.txt", "x")
        fake.folders.add("${base}/Archiv".toString())

        when:
        def root = api.call(setup.token, "GET", "${STORES}/${setup.store.id}/folders") as Map
        def projects = api.call(setup.token, "GET", "${STORES}/${setup.store.id}/folders?path=/Projekte//") as Map

        then:
        root == [path: "", folders: ["Archiv", "Handbuch", "Projekte"], truncated: false]
        projects == [path: "Projekte", folders: ["2025", "2026"], truncated: false]
    }

    def "at most 500 folders, the rest cut off and said"() {
        given:
        def setup = sources.tenant()
        (1..501).each { fake.folders.add("${setup.base}/Ordner-${String.format('%03d', it)}".toString()) }

        when:
        def listing = api.call(setup.token, "GET", "${STORES}/${setup.store.id}/folders") as Map

        then:
        (listing.folders as List).size() == 500
        listing.truncated
    }

    def "a path above the store's folder, a store not confirmed, a server out of reach, another tenant's store"() {
        given:
        def setup = sources.tenant()
        def other = sources.tenant()
        def unconfirmed = api.call(setup.token, "POST", STORES, [name: "Neu", type: "SFTP", host: "localhost",
                username: "check", password: "check-secret", basePath: setup.base]) as Map

        expect:
        sources.refusal(setup.token, "GET", "${STORES}/${setup.store.id}/folders?path=../oben") == [400, "fileStore.folderInvalid"]
        sources.refusal(setup.token, "GET", "${STORES}/${setup.store.id}/folders?path=a/./b") == [400, "fileStore.folderInvalid"]
        sources.refusal(setup.token, "GET", "${STORES}/${unconfirmed.id}/folders") == [409, "fileStore.notReady"]
        api.status(other.token, "GET", "${STORES}/${setup.store.id}/folders") == HttpStatus.NOT_FOUND

        when:
        fake.unreachable = true

        then:
        sources.refusal(setup.token, "GET", "${STORES}/${setup.store.id}/folders") == [502, "fileStore.unreachable"]
    }

    def "a folder that does not exist answers its code"() {
        given:
        def setup = sources.tenant()

        expect:
        sources.refusal(setup.token, "GET", "${STORES}/${setup.store.id}/folders?path=gibt-es-nicht") ==
                [502, "fileStore.folderNotFound"]
    }

    def "the admin lists a tenant's folders like its tenant admins"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/Projekte/a.txt", "x")

        expect:
        (api.admin("GET", "/admin/tenants/${setup.tenant.id}/file-stores/${setup.store.id}/folders") as Map).folders ==
                ["Projekte"]
    }
}
