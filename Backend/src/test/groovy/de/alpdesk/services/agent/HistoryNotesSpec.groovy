package de.alpdesk.services.agent

import spock.lang.Specification

/** The notes the history puts on earlier answers never end up in an answer a model copied them into. */
class HistoryNotesSpec extends Specification {

    def "a line that is nothing but a note is left out: #reason"() {
        expect:
        AgentLoop.withoutHistoryNotes(answer) == stored

        where:
        reason                       | answer                                                              | stored
        "a created file"             | "Hier ist die Karte.\n\n[Created with a tool: karte.png]"           | "Hier ist die Karte."
        "a stopped call"             | "[Stopped at the call Buchhaltung cancel - run]\nErledigt."         | "Erledigt."
        "no text"                    | "[No text]"                                                         | ""
        "a note in a sentence stays" | "Ich habe [Created with a tool: x.png] notiert."                    | "Ich habe [Created with a tool: x.png] notiert."
        "text after a note stays"    | "[Created with a tool: x.png] und mehr"                             | "[Created with a tool: x.png] und mehr"
        "other brackets stay"        | "[Quelle: Handbuch.pdf]"                                            | "[Quelle: Handbuch.pdf]"
    }
}
