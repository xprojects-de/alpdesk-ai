package de.alpdesk.services.filestore

import com.sun.net.httpserver.HttpServer
import de.alpdesk.services.common.ServiceException
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * The real S3StoreClient against an HTTP server of the spec that lists a bucket like S3 - and one that never stops:
 * a store a tenant admin runs must not fill the memory every tenant shares - and one that breaks off.
 */
class S3StoreClientSpec extends Specification {

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    S3StoreClient client = new S3StoreClient(new FileStoreAddresses("127.0.0.1"))

    /** Keys per page, and the page after which the listing ends - null: never. */
    int keysPerPage
    Integer lastPage
    /** The page that says more is to come but names no token. */
    Integer tokenMissingOn
    int requests

    def setup() {
        server.createContext("/") { exchange ->
            requests++
            int page = (exchange.requestURI.query =~ /continuation-token=(\d+)/).with { it.find() ? it.group(1) as int : 0 }
            byte[] answer = listing(page).getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/xml")
            exchange.sendResponseHeaders(200, answer.length)
            exchange.responseBody.write(answer)
            exchange.close()
        }
        server.start()
    }

    def cleanup() {
        server.stop(0)
        client.close()
    }

    def "a listing over several pages returns every entry"() {
        given:
        keysPerPage = 3
        lastPage = 2

        expect:
        list().size() == 9
    }

    def "a store that never stops listing is refused - with full pages and with empty ones"() {
        given:
        keysPerPage = keys
        lastPage = null

        when:
        list()

        then:
        def e = thrown(ServiceException)
        e.code == "fileStore.tooManyEntries"
        requests <= 1001

        where:
        keys << [1000, 0]
    }

    def "a page announcing more without a token to ask for it fails the listing - the pages before are not the folder"() {
        given:
        keysPerPage = 3
        lastPage = 2
        tokenMissingOn = 1

        when:
        list()

        then:
        def e = thrown(ServiceException)
        e.code == "fileStore.readFailed"
    }

    private List<RemoteFile> list() {
        def settings = new S3Settings("http://127.0.0.1:${server.address.port}", "eu-central-1", "logs", "/", "AKIAEXAMPLE")
        try (def session = client.open(settings, "secret-example")) {
            return session.list("/")
        }
    }

    private String listing(int page) {
        boolean truncated = lastPage == null || page < lastPage
        def contents = (0..<keysPerPage).collect {
            "<Contents><Key>p${page}-${it}.log</Key><LastModified>2026-10-01T00:00:00.000Z</LastModified><Size>1</Size></Contents>"
        }.join()
        """<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>logs</Name><Prefix></Prefix>\
<KeyCount>${keysPerPage}</KeyCount><MaxKeys>1000</MaxKeys><Delimiter>/</Delimiter><IsTruncated>${truncated}</IsTruncated>\
${contents}${truncated && page != tokenMissingOn ? "<NextContinuationToken>${page + 1}</NextContinuationToken>" : ""}</ListBucketResult>"""
    }
}
