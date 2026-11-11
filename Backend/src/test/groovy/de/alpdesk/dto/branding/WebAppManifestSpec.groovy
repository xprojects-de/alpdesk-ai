package de.alpdesk.dto.branding

import de.alpdesk.services.branding.Branding
import de.alpdesk.services.branding.BrandingService
import spock.lang.Specification

/** The home screen's icon: the installation's logo - without one (APP_BRANDING_LOGO left empty) the frontend's spark. */
class WebAppManifestSpec extends Specification {

    def "the icon is #expected.src when the installation #has a logo"() {
        when:
        def manifest = WebAppManifest.from(new Branding("Werkstattwissen", "#0055aa", logo.present), logo)

        then:
        manifest.icons() == [expected]

        where:
        has       | logo                                                              | expected
        "has"     | Optional.of(new BrandingService.Logo(new byte[1], "image/webp")) | new WebAppManifest.Icon("logo", "image/webp", "any")
        "has not" | Optional.empty()                                                  | new WebAppManifest.Icon("../apple-touch-icon.png", "image/png", "180x180")
    }
}
