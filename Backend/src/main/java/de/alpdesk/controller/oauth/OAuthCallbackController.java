package de.alpdesk.controller.oauth;

import de.alpdesk.security.RequestOrigin;
import de.alpdesk.services.branding.BrandingService;
import de.alpdesk.services.oauth.OAuthAddresses;
import de.alpdesk.services.oauth.OAuthClient;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The two addresses of a connection's sign-in an authorization server and the admin's browser reach without a session
 * (docs/architecture/mcp.md "Signing in to the software's server"): the browser's way back from the server's sign-in
 * page - it only sends the browser on to the page the sign-in started on, with the server's answer; that page
 * completes it with the session of the person who started it - and our client id metadata document, which a server
 * that reads one fetches from here.
 */
@Hidden
@Controller("/oauth")
@Secured(SecurityRule.IS_ANONYMOUS)
public class OAuthCallbackController {

    private final OAuthClient oauth;
    private final OAuthAddresses addresses;
    private final BrandingService brandings;

    public OAuthCallbackController(OAuthClient oauth, OAuthAddresses addresses, BrandingService brandings) {
        this.oauth = oauth;
        this.addresses = addresses;
        this.brandings = brandings;
    }

    /**
     * On to the page the sign-in started on, with the connection and the server's answer as it came - nothing is
     * exchanged here: an answer someone else's browser brings back (a sign-in link passed on) must sign in no one. A
     * state nobody started has nowhere to go - a short plain page.
     */
    @Get("/connections/callback")
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<?> callback(HttpRequest<?> request) {
        String query = request.getUri().getRawQuery();
        Optional<OAuthClient.Returning> returning = oauth.returning(query);
        if (returning.isEmpty()) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN_TYPE)
                    .body("This sign-in is no longer valid - start it again on the Software page.");
        }
        String page = returning.get().returnUrl();
        String back = page + (page.contains("?") ? "&" : "?") + "connection=" + returning.get().connectionId()
                + "&signIn=complete&" + query;
        return HttpResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, back);
    }

    /**
     * Our client id metadata document: its own address is the client id, the callback the one way back. A server may
     * keep it for an hour.
     */
    @Get(value = "/client-metadata.json", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> clientMetadata(HttpRequest<?> request) {
        String origin = RequestOrigin.of(request);
        return HttpResponse.ok(Map.<String, Object>of(
                        "client_id", addresses.metadataDocument(origin),
                        "client_name", brandings.installation().name(),
                        "client_uri", origin + addresses.contextPath() + "/",
                        "logo_uri", origin + addresses.contextPath() + "/branding/logo",
                        "redirect_uris", List.of(addresses.callback(origin)),
                        "grant_types", List.of("authorization_code", "refresh_token"),
                        "response_types", List.of("code"),
                        "token_endpoint_auth_method", "none"))
                .header("Cache-Control", "public, max-age=3600");
    }
}
