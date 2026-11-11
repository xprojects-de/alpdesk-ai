package de.alpdesk.services.chat

import spock.lang.Specification

/** What a model writes as a conversation's title, and what the list shows of it. */
class ConversationTitlesSpec extends Specification {

    def "the title loses what models add: #written"() {
        expect:
        ConversationTitles.clean(written) == title

        where:
        written                                  || title
        "Riesenslalom stecken"                   || "Riesenslalom stecken"
        "\"Riesenslalom stecken\""               || "Riesenslalom stecken"
        "„Riesenslalom stecken“."                || "Riesenslalom stecken"
        "**Titel:** Krafttraining U14"           || "Krafttraining U14"
        "Title: Weekly plan"                     || "Weekly plan"
        "# Mikrozyklus Kraft\n\nWeitere Zeilen" || "Mikrozyklus Kraft"
        "Was ist ein Mikrozyklus?"               || "Was ist ein Mikrozyklus?"
        "  "                                     || null
        null                                     || null
    }

    def "a title too long is cut"() {
        expect:
        ConversationTitles.clean("x" * 100).length() == ConversationTitles.MAX_LENGTH
        ConversationTitles.clean("x" * 100).endsWith("…")
    }
}
