package de.alpdesk.controller.branding

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.branding.BrandingService
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/** The installation's look comes from app.branding.* - the environment of the container - with a logo file. */
@MicronautTest(transactional = false)
@Property(name = "app.branding.name", value = "Werkstattwissen")
@Property(name = "app.branding.primary-color", value = "#0055AA")
@Property(name = "app.branding.logo", value = "src/test/resources/branding/logo.png")
class BrandingDefaultsSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    def "the environment sets the defaults and the logo, which a tenant without its own look gets too"() {
        given:
        def api = new AiApi(client)
        def user = api.userToken(api.tenant().id)

        expect:
        api.call(null, "GET", "/branding") == [name: "Werkstattwissen", primaryColor: "#0055aa", hasLogo: true]
        api.download(null, "/branding/logo").body() == BrandingSpec.PNG
        api.call(user, "GET", "/tenant/branding") == [name: "Werkstattwissen", primaryColor: "#0055aa", hasLogo: true]
        api.download(user, "/tenant/branding/logo").body() == BrandingSpec.PNG
    }

    def "the web app manifest carries the environment's name and colour"() {
        given:
        def manifest = new AiApi(client).call(null, "GET", "/branding/manifest.webmanifest") as Map

        expect:
        manifest.name == "Werkstattwissen"
        manifest.short_name == "Werkstattwissen"
        manifest.theme_color == "#0055aa"
        manifest.icons == [[src: "logo", type: "image/png", sizes: "any"]]
    }

    def "an invalid default stops the start: #reason"() {
        when:
        new BrandingService(null, null, null, name, color, logo)

        then:
        thrown(IllegalStateException)

        where:
        reason                | name   | color     | logo
        "no name"             | " "    | "#000000" | ""
        "a colour by name"    | "Firma" | "blue"   | ""
        "a missing logo file" | "Firma" | "#000000" | "does/not/exist.png"
        "a missing resource"  | "Firma" | "#000000" | "classpath:branding/none.png"
    }
}
