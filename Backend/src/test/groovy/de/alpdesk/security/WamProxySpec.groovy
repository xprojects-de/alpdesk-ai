package de.alpdesk.security

import io.micronaut.http.HttpRequest
import spock.lang.Specification

/** What the WAM proxy sends becomes a username - a Windows domain account included - and a list of IDM groups. */
class WamProxySpec extends Specification {

    def "the IDM groups are split at the separator #separator, trimmed, empty ones left out - also from several lines"() {
        given:
        def proxy = new WamProxy(true, "X-Remote-User", "", "X-Remote-Groups", separator)
        def request = HttpRequest.GET("/")
        lines.each { request.header("X-Remote-Groups", it) }

        expect:
        proxy.checksGroups()
        proxy.groups(request) == groups

        where:
        separator | lines                                        || groups
        ","       | ["Gruppe1,Gruppe2"]                          || ["Gruppe1", "Gruppe2"]
        ","       | ["Gruppe1 , ,Gruppe2,"]                      || ["Gruppe1", "Gruppe2"]
        ","       | ["Gruppe1", "Gruppe2"]                       || ["Gruppe1", "Gruppe2"]
        ";"       | ["CN=Vertrieb,OU=Firma;CN=Technik,OU=Firma"] || ["CN=Vertrieb,OU=Firma", "CN=Technik,OU=Firma"]
        ","       | []                                           || []
    }

    def "the groups are checked only with WAM on and a groups header set"() {
        expect:
        new WamProxy(enabled, "X-Remote-User", "", header, ",").checksGroups() == checked

        where:
        enabled | header            || checked
        true    | "X-Remote-Groups" || true
        true    | "  "              || false
        false   | "X-Remote-Groups" || false
    }

    def "the proxy's user #proxyUser becomes the username #username"() {
        expect:
        WamProxy.asUsername(proxyUser) == username

        where:
        proxyUser               || username
        "abc1xyz"               || "abc1xyz"
        "abc1xyz@example.com"   || "abc1xyz@example.com"
        "CORP\\abc1xyz"         || "abc1xyz@corp"
        "Europe\\Hans.Muster"   || "Hans.Muster@europe"
        // Not a domain account - left as it is, and refused as a username where it is used.
        "\\maria"               || "\\maria"
        "CORP\\"                || "CORP\\"
        "A\\b\\c"               || "b\\c@a"
    }
}
