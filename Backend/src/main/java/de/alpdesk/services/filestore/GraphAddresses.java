package de.alpdesk.services.filestore;

import jakarta.inject.Singleton;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.util.Locale;

/**
 * Microsoft's addresses a SharePoint store is reached at - fixed, not a setting - and the TLS they are trusted with; the
 * specs replace this bean with a server of their own and its certificate.
 */
@Singleton
public class GraphAddresses {

    private static final URI LOGIN = URI.create("https://login.microsoftonline.com");
    private static final URI GRAPH = URI.create("https://graph.microsoft.com/v1.0");

    /** Where the app's token comes from. */
    public URI login() {
        return LOGIN;
    }

    /** Graph's API, without a trailing slash. */
    public URI graph() {
        return GRAPH;
    }

    /**
     * Whether a download address Graph handed out may be fetched: HTTPS to a SharePoint host only. It carries its own
     * authorization - the token never goes with it.
     */
    public boolean downloadAllowed(URI address) {
        String host = address.getHost() == null ? "" : address.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(address.getScheme()) && host.endsWith(".sharepoint.com");
    }

    /** The certificates the connections to Microsoft trust: the JVM's (with USE_SYSTEM_CA_CERTS the company's too). */
    public SSLSocketFactory sslSocketFactory() {
        return HttpsURLConnection.getDefaultSSLSocketFactory();
    }

    /** Whether an address (the next page of a listing) is Graph's own - only then does the token go with it. */
    public boolean isGraph(URI address) {
        return address.toString().startsWith(graph() + "/");
    }
}
