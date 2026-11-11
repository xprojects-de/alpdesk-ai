package de.alpdesk.services.agent

import spock.lang.Specification

/** An answer that was not streamed loses the runs of blanks a model can get caught in. */
class BlankRunsSpec extends Specification {

    def "runs of 200 blanks and more become one; shorter ones stay"() {
        expect:
        AgentLoop.withoutBlankRuns("| Abschnitt" + " " * 366_000) == "| Abschnitt"
        AgentLoop.withoutBlankRuns("a" + " " * 250 + "b") == "a b"
        AgentLoop.withoutBlankRuns("| a   | b |") == "| a   | b |"
    }
}
