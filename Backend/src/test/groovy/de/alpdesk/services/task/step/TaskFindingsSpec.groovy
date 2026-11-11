package de.alpdesk.services.task.step

import de.alpdesk.entities.task.RunFile
import de.alpdesk.services.task.TaskDefinition.CheckPoint
import de.alpdesk.services.task.TaskDefinition.Severity
import io.micronaut.serde.ObjectMapper
import spock.lang.Specification

/** How a step's answer becomes its result (TaskFindings): the room a note has, by its check point's severity. */
class TaskFindingsSpec extends Specification {

    def "a #severity note keeps #kept characters - a report lists every group, a finding states one problem"() {
        given:
        def check = new CheckPoint("A", severity, "Instruction")
        def files = [new RunFile(1L, "app.log", [new RunFile.Line(1L, "ERROR x")])]
        String answer = ObjectMapper.getDefault().writeValueAsString([checks: [[name  : "A", status: "finding",
                                                                                note  : "n" * 7000,
                                                                                evidence: [[file: "app.log", line: 1]]]]])

        when:
        def result = TaskFindings.read(answer, [check], files, ObjectMapper.getDefault())

        then:
        String note = TaskFindings.checks(result)[0].note
        note.startsWith("n" * kept)
        note.endsWith(" [...]")
        note.length() == kept + " [...]".length()

        where:
        severity         || kept
        Severity.ERROR   || 1000
        Severity.WARNING || 1000
        Severity.REPORT  || 6000
    }
}
