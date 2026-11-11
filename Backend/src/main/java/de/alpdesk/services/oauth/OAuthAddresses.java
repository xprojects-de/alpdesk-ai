package de.alpdesk.services.oauth;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The installation's own addresses in a sign-in (docs/architecture/mcp.md "Signing in to the software's server"),
 * built from the origin the admin's browser reached us at and the context path - no setting: the browser's way back
 * from the server's sign-in page, our client id metadata document, and the page of the frontend to return to.
 */
@Singleton
public class OAuthAddresses {

    public static final String CALLBACK = "/oauth/connections/callback";
    public static final String METADATA_DOCUMENT = "/oauth/client-metadata.json";

    /**
     * The frontend's two areas below the context path (SpaFallbackController), in plain segments only: no "..", no
     * encoded character a browser would resolve into another path.
     */
    private static final Pattern FRONTEND_PAGE = Pattern.compile("/(app|dashboard)(/[A-Za-z0-9_-]+)*");

    private final String contextPath;

    public OAuthAddresses(@Value("${micronaut.server.context-path:}") @Nullable String contextPath) {
        this.contextPath = contextPath == null ? "" : contextPath.strip().replaceAll("/+$", "");
    }

    public String callback(String origin) {
        return origin + contextPath + CALLBACK;
    }

    public String metadataDocument(String origin) {
        return origin + contextPath + METADATA_DOCUMENT;
    }

    public String contextPath() {
        return contextPath;
    }

    /**
     * The frontend's page to come back to: absolute http(s), no user info, query or fragment, on the host the request
     * came to (any port - in development the frontend and the backend have their own), and a page of the frontend
     * ({@link #FRONTEND_PAGE}). 400 otherwise: the callback sends the browser there with the server's answer, the code
     * included - never elsewhere, and never to a path that serves a tenant's own content (a handbook's website with
     * its scripts), which could pass the code on.
     */
    public String returnUrl(@Nullable String returnUrl, String origin) {
        URI uri;
        try {
            uri = returnUrl == null ? null : new URI(returnUrl.strip());
        } catch (Exception e) {
            uri = null;
        }
        String ownHost = URI.create(origin).getHost();
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"))
                || ownHost == null || !uri.getHost().toLowerCase(Locale.ROOT).equals(ownHost.toLowerCase(Locale.ROOT))
                || !frontendPage(uri.getRawPath())) {
            throw ServiceException.badRequest("oauth.returnUrlForeign",
                    "The page to return to after the sign-in must be a page of this installation");
        }
        return uri.toString();
    }

    private boolean frontendPage(@Nullable String path) {
        return path != null && path.startsWith(contextPath) && FRONTEND_PAGE.matcher(path.substring(contextPath.length()))
                .matches();
    }
}
