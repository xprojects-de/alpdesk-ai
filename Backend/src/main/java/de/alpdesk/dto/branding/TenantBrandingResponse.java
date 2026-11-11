package de.alpdesk.dto.branding;

import de.alpdesk.entities.branding.TenantBranding;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Optional;

/**
 * What the admin set for a tenant - null where it uses the installation's default - and those defaults, so the dialog
 * can show what an empty value means; loginKey: the tenant's own login page is /login/&lt;loginKey&gt;.
 */
@Serdeable
public record TenantBrandingResponse(
        @Nullable String name,
        @Nullable String primaryColor,
        boolean hasLogo,
        String defaultName,
        String defaultPrimaryColor,
        String loginKey
) {
    public static TenantBrandingResponse from(Optional<TenantBranding> branding, String defaultName, String defaultColor,
                                              String loginKey) {
        return new TenantBrandingResponse(branding.map(TenantBranding::name).orElse(null),
                branding.map(TenantBranding::primaryColor).orElse(null),
                branding.map(TenantBranding::logoType).isPresent(), defaultName, defaultColor, loginKey);
    }
}
