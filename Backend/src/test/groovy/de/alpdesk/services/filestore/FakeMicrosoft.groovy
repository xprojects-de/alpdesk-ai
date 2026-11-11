package de.alpdesk.services.filestore

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Microsoft's login and Graph for the SharePoint specs (test.fake-graph=true): an HTTP server of the spec answering the
 * calls GraphStoreClient makes - the token (client credentials), a site by its address, its libraries, a folder's
 * children page by page, an item's download address and the download with a range. It refuses like Entra ID and Graph
 * do (unknown directory, app or secret, an expired secret, a site not granted) and can throttle, send a whole file for a
 * range, hand out a download address on a foreign host and let tokens expire. It serves HTTPS with a certificate of
 * its own, which only the connections of the spec trust (MSAL takes no other authority). Downloads are allowed from
 * 127.0.0.1 only - "localhost" is the foreign host.
 */
@Singleton
@Replaces(GraphAddresses)
@Requires(property = "test.fake-graph", value = "true")
class FakeMicrosoft extends GraphAddresses {

    static final String DIRECTORY = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    static final String CLIENT = "11111111-2222-3333-4444-555555555555"
    static final String SECRET = "app-secret-value"
    static final String SITE = "https://musterfirma.sharepoint.com/sites/projekte"
    static final String ONEDRIVE = "https://musterfirma-my.sharepoint.com/personal/anna_musterfirma_example"
    /** A site that exists, but the app was not granted (Sites.Selected). */
    static final String SITE_NOT_GRANTED = "https://musterfirma.sharepoint.com/sites/geheim"
    static final String DEFAULT_LIBRARY = "Dokumente"
    static final String OTHER_LIBRARY = "Archiv"

    private final HttpsServer http = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    private final SSLSocketFactory trusting
    private final AtomicInteger tokens = new AtomicInteger()

    /** Files by drive and absolute path. */
    final Map<String, Map<String, byte[]>> files = new ConcurrentHashMap<>()
    /** Folders by drive (absolute paths) besides those of the files. */
    final Map<String, Set<String>> folders = new ConcurrentHashMap<>()
    /** Files open in Office somewhere: an upload answers 423. */
    final Set<String> locked = ConcurrentHashMap.<String> newKeySet()
    /** What came in: [method, path, authorization, range]. */
    final List<Map> requests = new CopyOnWriteArrayList<>()

    volatile boolean secretExpired = false
    /** Entries per page of a listing - small, so a listing pages. */
    volatile int pageSize = 3
    /** How many answers are 429 before Graph answers again, and the Retry-After they name. */
    volatile int throttle = 0
    volatile String retryAfter = "1"
    /** A range is answered with the whole file (200). */
    volatile boolean ignoresRange = false
    /** Download addresses point at a foreign host. */
    volatile boolean foreignDownload = false
    /** Tokens issued before this number are expired. */
    volatile int validFrom = 0
    /** A next page of a listing answered with 200 and a proxy's HTML page instead of Graph's JSON. */
    volatile boolean brokenNextPage = false

    FakeMicrosoft() {
        def keys = KeyPairGenerator.getInstance("RSA").with { initialize(2048); generateKeyPair() }
        def name = new X500Name("CN=127.0.0.1")
        long now = System.currentTimeMillis()
        def builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), new Date(now - 86_400_000),
                new Date(now + 86_400_000), name, keys.public)
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")))
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
        def own = KeyStore.getInstance("PKCS12").tap { load(null) }
        own.setKeyEntry("fake", keys.private, "fake".toCharArray(), [certificate] as Certificate[])
        def keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm).tap { init(own, "fake".toCharArray()) }
        def server = SSLContext.getInstance("TLS").tap { init(keyManagers.keyManagers, null, null) }
        http.setHttpsConfigurator(new HttpsConfigurator(server))
        def trusted = KeyStore.getInstance("PKCS12").tap { load(null) }
        trusted.setCertificateEntry("fake", certificate)
        def trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm).tap { init(trusted) }
        trusting = SSLContext.getInstance("TLS").tap { init(null, trustManagers.trustManagers, null) }.socketFactory
        http.createContext("/") { HttpExchange exchange -> answer(exchange) }
        http.start()
        reset()
    }

    void reset() {
        files.clear()
        locked.clear()
        folders.clear()
        requests.clear()
        secretExpired = false
        pageSize = 3
        throttle = 0
        retryAfter = "1"
        ignoresRange = false
        foreignDownload = false
        brokenNextPage = false
        validFrom = tokens.get()
        ["projekte-dokumente", "projekte-archiv", "onedrive-dokumente"].each { folders[it] = ConcurrentHashMap.newKeySet() }
    }

    void write(String drive, String path, String content) {
        files.computeIfAbsent(drive) { new ConcurrentHashMap<>() }[path] = content.getBytes("UTF-8")
    }

    void folder(String drive, String path) {
        folders[drive] << path
    }

    void expireTokens() {
        validFrom = tokens.get() + 1
    }

    @Override
    URI login() {
        URI.create("https://127.0.0.1:${http.address.port}")
    }

    @Override
    URI graph() {
        URI.create("https://127.0.0.1:${http.address.port}/v1.0")
    }

    @Override
    boolean downloadAllowed(URI address) {
        address.scheme == "https" && address.host == "127.0.0.1"
    }

    @Override
    SSLSocketFactory sslSocketFactory() {
        trusting
    }

    private void answer(HttpExchange exchange) {
        String path = exchange.requestURI.rawPath
        requests << [method       : exchange.requestMethod, path: path,
                     authorization: exchange.requestHeaders.getFirst("Authorization"),
                     range        : exchange.requestHeaders.getFirst("Range")]
        if (path.endsWith("/oauth2/v2.0/token")) {
            token(exchange, path)
        } else if (path.startsWith("/download/")) {
            download(exchange, path)
        } else if (path.startsWith("/v1.0/")) {
            graphCall(exchange, path.substring("/v1.0".length()))
        } else {
            respond(exchange, 404, [:])
        }
    }

    private void token(HttpExchange exchange, String path) {
        Map<String, String> form = exchange.requestBody.text.split("&").collectEntries {
            def pair = it.split("=", 2)
            [(URLDecoder.decode(pair[0], "UTF-8")): URLDecoder.decode(pair.length > 1 ? pair[1] : "", "UTF-8")]
        }
        String directory = path.split("/")[1]
        if (directory != DIRECTORY) {
            refuse(exchange, 400, "invalid_request", 90002, "Tenant not found.")
        } else if (form.client_id != CLIENT) {
            refuse(exchange, 400, "unauthorized_client", 700016, "Application was not found in the directory.")
        } else if (secretExpired) {
            refuse(exchange, 401, "invalid_client", 7000222, "The provided client secret keys are expired.")
        } else if (form.client_secret != SECRET || !form.scope?.contains("https://graph.microsoft.com/.default")) {
            refuse(exchange, 401, "invalid_client", 7000215, "Invalid client secret provided.")
        } else {
            respond(exchange, 200, [access_token: "token-" + tokens.incrementAndGet(), token_type: "Bearer",
                                    expires_in  : 3599])
        }
    }

    /** A refusal of the login the way Entra ID words it: the number in the text and among the codes. */
    private static void refuse(HttpExchange exchange, int status, String error, int code, String text) {
        respond(exchange, status, [error            : error, error_codes: [code],
                                   error_description: "AADSTS${code}: ${text}".toString()])
    }

    private void graphCall(HttpExchange exchange, String path) {
        if (throttle > 0) {
            throttle--
            exchange.responseHeaders.add("Retry-After", retryAfter)
            respond(exchange, 429, [error: [code: "TooManyRequests"]])
            return
        }
        String auth = exchange.requestHeaders.getFirst("Authorization") ?: ""
        int token = auth.startsWith("Bearer token-") ? auth.substring("Bearer token-".length()) as int : -1
        if (token < 1 || token < validFrom) {
            respond(exchange, 401, [error: [code: "InvalidAuthenticationToken"]])
            return
        }
        if (path.startsWith("/sites/")) {
            site(exchange, path)
        } else if (path.startsWith("/drives/")) {
            drive(exchange, path)
        } else {
            respond(exchange, 404, [error: [code: "itemNotFound"]])
        }
    }

    private void site(HttpExchange exchange, String path) {
        String rest = path.substring("/sites/".length())
        if (rest.endsWith("/drive")) {
            respond(exchange, 200, [id: rest.replace("/drive", "") + "-dokumente"])
        } else if (rest.endsWith("/drives")) {
            String site = rest.replace("/drives", "")
            respond(exchange, 200, [value: [[id: site + "-dokumente", name: DEFAULT_LIBRARY],
                                            [id: site + "-archiv", name: OTHER_LIBRARY]]])
        } else {
            String address = "https://" + rest.replace(":", "")
            if (address == SITE) {
                respond(exchange, 200, [id: "projekte"])
            } else if (address == ONEDRIVE) {
                respond(exchange, 200, [id: "onedrive"])
            } else if (address == SITE_NOT_GRANTED) {
                respond(exchange, 403, [error: [code: "accessDenied"]])
            } else {
                respond(exchange, 404, [error: [code: "itemNotFound"]])
            }
        }
    }

    /** /drives/{d}/root/children, /drives/{d}/root:/a/b:/children, /drives/{d}/root:/a/b.log, /drives/{d}/page. */
    private void drive(HttpExchange exchange, String path) {
        String drive = path.split("/")[2]
        String rest = path.substring(("/drives/" + drive).length())
        if (rest == "/page" && brokenNextPage) {
            byte[] html = "<html><body>Proxy</body></html>".getBytes("UTF-8")
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.length)
            exchange.responseBody.write(html)
            exchange.close()
        } else if (rest == "/page") {
            Map<String, String> query = queryOf(exchange)
            children(exchange, drive, query.folder, query.skip as int)
        } else if (rest == "/root/children") {
            children(exchange, drive, "/", 0)
        } else if (rest.startsWith("/root:") && rest.endsWith(":/content") && exchange.requestMethod == "PUT") {
            upload(exchange, drive, decode(rest.substring("/root:".length(), rest.length() - ":/content".length())))
        } else if (rest.startsWith("/root:") && rest.endsWith(":/children")) {
            children(exchange, drive, decode(rest.substring("/root:".length(), rest.length() - ":/children".length())), 0)
        } else if (rest.startsWith("/root:")) {
            item(exchange, drive, decode(rest.substring("/root:".length())))
        } else {
            respond(exchange, 404, [error: [code: "itemNotFound"]])
        }
    }

    /** The simple upload: the item replaced whole; 423 for a file locked, 404 when its folder is missing. */
    private void upload(HttpExchange exchange, String drive, String path) {
        String folder = path.contains("/") && path.lastIndexOf("/") > 0 ? path.substring(0, path.lastIndexOf("/")) : "/"
        byte[] content = exchange.requestBody.readAllBytes()
        if (path in locked) {
            respond(exchange, 423, [error: [code: "resourceLocked"]])
        } else if (folder != "/" && !(folder in folderSet(drive))) {
            respond(exchange, 404, [error: [code: "itemNotFound"]])
        } else {
            files.computeIfAbsent(drive) { new ConcurrentHashMap<>() }[path] = content
            respond(exchange, 200, [name: path.substring(path.lastIndexOf("/") + 1), size: content.length, file: [:]])
        }
    }

    private void children(HttpExchange exchange, String drive, String folder, int skip) {
        if (folder != "/" && !(folder in folderSet(drive))) {
            respond(exchange, 404, [error: [code: "itemNotFound"]])
            return
        }
        String prefix = folder == "/" ? "/" : folder + "/"
        List<Map> items = []
        folderSet(drive).findAll { it.startsWith(prefix) && !it.substring(prefix.length()).contains("/") }.each {
            items << [name: it.substring(prefix.length()), folder: [childCount: 0]]
        }
        (files[drive] ?: [:]).each { String file, byte[] content ->
            if (file.startsWith(prefix) && !file.substring(prefix.length()).contains("/")) {
                String name = file.substring(prefix.length())
                items << (name.endsWith(".one")
                        ? [name: name, size: content.length, "package": [type: "oneNote"]]
                        : [name: name, size: content.length, lastModifiedDateTime: "2026-10-29T08:00:00Z", file: [:]])
            }
        }
        items.sort { it.name }
        int top = Math.min(pageSize, (queryOf(exchange).'$top' ?: "999") as int)
        List<Map> page = items.drop(skip).take(top)
        Map body = [value: page]
        if (skip + top < items.size()) {
            body."@odata.nextLink" = "${graph()}/drives/${drive}/page?folder=${URLEncoder.encode(folder, "UTF-8")}&skip=${skip + top}".toString()
        }
        respond(exchange, 200, body)
    }

    private void item(HttpExchange exchange, String drive, String path) {
        if (!(files[drive] ?: [:]).containsKey(path)) {
            respond(exchange, 404, [error: [code: "itemNotFound"]])
            return
        }
        String host = foreignDownload ? "localhost" : "127.0.0.1"
        String download = "https://${host}:${http.address.port}/download/${drive}${path.split("/").collect { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.join("/")}?sig=pre-authenticated"
        respond(exchange, 200, [name: path.substring(path.lastIndexOf("/") + 1), "@microsoft.graph.downloadUrl": download])
    }

    private void download(HttpExchange exchange, String rawPath) {
        String rest = rawPath.substring("/download/".length())
        String drive = rest.substring(0, rest.indexOf("/"))
        byte[] content = (files[drive] ?: [:])[decode(rest.substring(drive.length()))]
        if (content == null) {
            respond(exchange, 404, [:])
            return
        }
        String range = exchange.requestHeaders.getFirst("Range")
        if (range == null || ignoresRange) {
            exchange.sendResponseHeaders(200, content.length == 0 ? -1 : content.length)
            exchange.responseBody.withStream { it.write(content) }
            return
        }
        def (from, to) = range.substring("bytes=".length()).split("-").collect { it as long }
        if (from >= content.length) {
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
            return
        }
        byte[] part = Arrays.copyOfRange(content, from as int, Math.min(content.length, (to as long) + 1) as int)
        exchange.responseHeaders.add("Content-Range", "bytes ${from}-${from + part.length - 1}/${content.length}")
        exchange.sendResponseHeaders(206, part.length)
        exchange.responseBody.withStream { it.write(part) }
    }

    private Set<String> folderSet(String drive) {
        Set<String> all = new HashSet<>(folders[drive] ?: [])
        (files[drive] ?: [:]).keySet().each { String file ->
            String parent = file.substring(0, file.lastIndexOf("/"))
            while (!parent.isEmpty()) {
                all << parent
                parent = parent.substring(0, parent.lastIndexOf("/"))
            }
        }
        all
    }

    private static Map<String, String> queryOf(HttpExchange exchange) {
        String query = exchange.requestURI.rawQuery ?: ""
        query.split("&").findAll { it }.collectEntries {
            def pair = it.split("=", 2)
            [(URLDecoder.decode(pair[0], "UTF-8")): URLDecoder.decode(pair.length > 1 ? pair[1] : "", "UTF-8")]
        }
    }

    private static String decode(String rawPath) {
        rawPath.split("/").collect { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.join("/")
    }

    private static void respond(HttpExchange exchange, int status, Map body) {
        byte[] bytes = ObjectMapper.getDefault().writeValueAsString(body).getBytes("UTF-8")
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length)
        exchange.responseBody.withStream { it.write(bytes) }
    }
}
