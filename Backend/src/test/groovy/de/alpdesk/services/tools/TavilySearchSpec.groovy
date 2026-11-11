package de.alpdesk.services.tools

import com.sun.net.httpserver.HttpServer
import io.micronaut.serde.ObjectMapper
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * The real TavilySearch - langchain4j's TavilyWebSearchEngine - against an HTTP server of the spec that answers like
 * Tavily: what goes out (the key, the query, the basic depth, no page's whole text) and how the hits come back.
 */
class TavilySearchSpec extends Specification {

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    List<Map> requests = []
    int status = 200

    def setup() {
        server.createContext("/") { exchange ->
            requests << [path   : exchange.requestURI.path,
                         headers: exchange.requestHeaders,
                         body   : ObjectMapper.getDefault().readValue(new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8), Map)]
            byte[] answer = status == 200 ? ANSWER.getBytes(StandardCharsets.UTF_8) : '{"detail":{"error":"Unauthorized"}}'.bytes
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, answer.length)
            exchange.responseBody.write(answer)
            exchange.close()
        }
        server.start()
    }

    def cleanup() {
        server.stop(0)
    }

    def "a search sends the key, the query and the basic depth, not asking for the pages' text - each hit with its extract"() {
        when:
        def pages = new TavilySearch("http://127.0.0.1:${server.address.port}/").search("tvly-key-1", "Mindestlohn 2026")

        then:
        requests.size() == 1
        requests[0].path == "/search"
        requests[0].body.query == "Mindestlohn 2026"
        requests[0].body.search_depth == "basic"
        !requests[0].body.include_raw_content
        requests[0].body.max_results == TavilySearch.HITS
        requests[0].body.api_key == "tvly-key-1"

        and:
        pages*.title() == ["Mindestlohn 2026", "Ohne Text"]
        pages*.url()*.toString() == ["https://example.com/mindestlohn", "https://example.org/ohne"]
        pages*.snippet() == ["Kurzfassung", "Nur der Ausschnitt"]
    }

    def "app.web-search.address points the search elsewhere - with or without a slash at its end"() {
        when:
        new TavilySearch(" http://127.0.0.1:${server.address.port}${ending} ").search("tvly-key-1", "Mindestlohn")

        then:
        requests*.path == ["/search"]

        where:
        ending << ["", "/"]
    }

    def "a refused key fails the search"() {
        given:
        status = 401

        when:
        new TavilySearch("http://127.0.0.1:${server.address.port}/").search("tvly-falsch", "Mindestlohn")

        then:
        thrown(RuntimeException)
    }

    static final String ANSWER = '''{"query": "Mindestlohn 2026", "results": [
        {"title": "Mindestlohn 2026", "url": "https://example.com/mindestlohn", "content": "Kurzfassung", "score": 0.9},
        {"title": "Ohne Text", "url": "https://example.org/ohne", "content": "Nur der Ausschnitt", "score": 0.5}]}'''
}
