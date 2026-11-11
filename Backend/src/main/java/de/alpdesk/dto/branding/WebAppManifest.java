package de.alpdesk.dto.branding;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.alpdesk.services.branding.Branding;
import de.alpdesk.services.branding.BrandingService;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Optional;

/**
 * The web app manifest of the installation (docs/architecture/branding.md "On a phone's home screen"): added to a
 * phone's home screen, the frontend opens as an app of its own with the installation's name, colour and logo. Served at
 * /branding/manifest.webmanifest - every address in it is relative to that one, so a context path stays in them.
 */
@Serdeable
public record WebAppManifest(
        String name,
        @JsonProperty("short_name") String shortName,
        @JsonProperty("start_url") String startUrl,
        String scope,
        String display,
        @JsonProperty("theme_color") String themeColor,
        @JsonProperty("background_color") String backgroundColor,
        List<Icon> icons
) {

    /** The frontend's root, seen from /branding/. */
    private static final String ROOT = "../";
    /** The dark background of the frontend (styles.scss, --app-bg): the splash screen while the app starts. */
    private static final String BACKGROUND = "#111311";
    /** The neutral spark of the frontend, when the installation has no logo of its own. */
    private static final Icon SPARK = new Icon(ROOT + "apple-touch-icon.png", "image/png", "180x180");

    @Serdeable
    public record Icon(String src, String type, String sizes) {
    }

    public static WebAppManifest from(Branding branding, Optional<BrandingService.Logo> logo) {
        Icon icon = logo.map(found -> new Icon("logo", found.contentType(), "any")).orElse(SPARK);
        return new WebAppManifest(branding.name(), branding.name(), ROOT, ROOT, "standalone", branding.primaryColor(),
                BACKGROUND, List.of(icon));
    }
}
