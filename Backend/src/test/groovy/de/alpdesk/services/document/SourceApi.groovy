package de.alpdesk.services.document

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.task.FakeStoreClient
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException

/**
 * The HTTP steps of the knowledge source specs, on top of AiApi: a tenant admin with an embedding model and its own
 * OpenAI key, and a confirmed store on a folder of its own in FakeStoreClient (test.fake-store=true).
 */
class SourceApi {

    static final String SOURCES = "/tenant/admin/knowledge-sources"

    final AiApi api
    final FakeStoreClient fake

    SourceApi(AiApi api, FakeStoreClient fake) {
        this.api = api
        this.fake = fake
    }

    /** [tenant, token (a tenant admin), store, base (the store's folder on the fake server)]. */
    Map tenant() {
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        String base = "/ablage-" + AiApi.unique()
        fake.folders.add(base)
        [tenant: tenant, token: token, store: store(token, base), base: base]
    }

    /** A store on this folder of the fake server, its fingerprint confirmed. */
    Map store(String token, String base, String name = "Ablage " + AiApi.unique()) {
        def store = api.call(token, "POST", "/tenant/admin/file-stores", [name: name, type: "SFTP", host: "localhost",
                port: 22, username: "check", password: "check-secret", basePath: base]) as Map
        api.call(token, "PUT", "/tenant/admin/file-stores/${store.id}/fingerprint", [fingerprint: FakeStoreClient.FINGERPRINT])
        store
    }

    /** A source on the setup's store - after its listing run. */
    Map source(Map setup, Map settings = [:]) {
        Map created = api.call(setup.token, "POST", SOURCES, [name: "Quelle " + AiApi.unique(),
                fileStoreId: setup.store.id] + settings) as Map
        await(setup.token, created.id)
    }

    /** Starts a run by hand and waits for its end: the source then. */
    Map run(String token, Object sourceId) {
        api.call(token, "POST", "${SOURCES}/${sourceId}/run")
        await(token, sourceId)
    }

    /** The source once no run is under way. */
    Map await(String token, Object sourceId) {
        long deadline = System.currentTimeMillis() + 20_000
        while (true) {
            Map source = (api.call(token, "GET", SOURCES) as List<Map>).find { it.id == sourceId }
            if (source == null || !source.running || System.currentTimeMillis() > deadline) {
                return source
            }
            Thread.sleep(50)
        }
    }

    /** The tenant's documents of this source, by path; each waited for until embedded. */
    Map<String, Map> documents(String token, Object sourceId) {
        List<Map> all = api.call(token, "GET", "/tenant/admin/documents") as List<Map>
        all.findAll { it.sourceId == sourceId }.collectEntries { document ->
            Map ready = api.awaitDocument(token, document.id)
            [(ready.folder ? ready.folder + "/" + ready.fileName : ready.fileName): ready]
        }
    }

    void write(String path, String content) {
        fake.write(path, content)
    }

    /** [status, code] of a refused call. */
    List refusal(String token, String method, String path, Map body = null) {
        try {
            def request = HttpRequest.create(HttpMethod.valueOf(method), path).bearerAuth(token)
            if (body != null || method in ["POST", "PUT"]) {
                request = request.body(body ?: [:])
            }
            api.client.toBlocking().exchange(request)
            throw new AssertionError("$method $path was not refused")
        } catch (HttpClientResponseException e) {
            [e.status.code, e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }
}
