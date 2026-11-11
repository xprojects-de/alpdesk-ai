package de.alpdesk.services.filestore

import de.alpdesk.services.ai.AiApi
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException

/** The HTTP steps of the file store specs (FileStoreSpec, FileStoreServerSpec), on top of AiApi. */
class FileStoreApi {

    static final String STORES = "/tenant/admin/file-stores"

    private final AiApi api
    private final HttpClient client

    FileStoreApi(AiApi api, HttpClient client) {
        this.api = api
        this.client = client
    }

    Map create(String token, Map settings) {
        api.call(token, "POST", STORES, settings) as Map
    }

    Map check(String token, Object storeId) {
        api.call(token, "POST", "${STORES}/${storeId}/check", [:]) as Map
    }

    Map confirm(String token, Object storeId, String fingerprint) {
        api.call(token, "PUT", "${STORES}/${storeId}/fingerprint", [fingerprint: fingerprint]) as Map
    }

    /** [status, code] of a refused call. */
    List refusal(String token, String method, String path, Map body) {
        try {
            client.toBlocking().exchange(HttpRequest.create(HttpMethod.valueOf(method), path).bearerAuth(token).body(body))
            throw new AssertionError("$method $path was not refused")
        } catch (HttpClientResponseException e) {
            [e.status, e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }

    /** A port on localhost nothing listens on. */
    static int closedPort() {
        def socket = new ServerSocket(0)
        int port = socket.localPort
        socket.close()
        port
    }
}
