package de.alpdesk.dto.branding;

import de.alpdesk.services.branding.Branding;
import io.micronaut.serde.annotation.Serdeable;

/** The look the frontend applies; hasLogo: the matching logo endpoint has one. */
@Serdeable
public record BrandingResponse(String name, String primaryColor, boolean hasLogo) {

    public static BrandingResponse from(Branding branding) {
        return new BrandingResponse(branding.name(), branding.primaryColor(), branding.hasLogo());
    }
}
