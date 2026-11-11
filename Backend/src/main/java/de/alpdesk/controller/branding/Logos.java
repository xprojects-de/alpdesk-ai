package de.alpdesk.controller.branding;

import de.alpdesk.services.branding.BrandingService;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;

import java.util.Optional;

/** A logo as a response; no-cache - a new logo shows at the next load, without a stale copy in the browser. */
final class Logos {

    private Logos() {
    }

    static HttpResponse<byte[]> response(Optional<BrandingService.Logo> logo) {
        return logo.map(found -> HttpResponse.ok(found.content())
                        .contentType(MediaType.of(found.contentType()))
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .orElseGet(HttpResponse::notFound);
    }
}
