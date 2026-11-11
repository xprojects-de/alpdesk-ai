package de.alpdesk.services.task.step

import de.alpdesk.entities.task.RunFile
import de.alpdesk.services.tools.ToolContext
import spock.lang.Specification

/**
 * How a step's inputs go to its model (StepInputs.filesText): under their steps, a step without lines named, a step cut
 * at its limit marked, and a file two steps gave with other filters - too large to go whole - one file for the tools,
 * its lines in the file's order.
 */
class StepInputsSpec extends Specification {

    def "the files stand under their steps, and a step without lines is named"() {
        given:
        def inputs = [new StepInputs.Input("failures", [file("app.log", [1L: "ERROR Timeout"])], false),
                      new StepInputs.Input("warnings", [], false)]

        when:
        String text = StepInputs.filesText(inputs, tools(), true)

        then:
        text.indexOf('=== Step "failures"') < text.indexOf('=== File "app.log"')
        text.indexOf('=== File "app.log"') < text.indexOf("1| ERROR Timeout")
        text.contains('=== Step "warnings" gave no lines this run')
    }

    def "a step cut at its limit says so - what comes after its lines was not read, and must not look missing"() {
        given:
        def inputs = [new StepInputs.Input("transfers", [file("info.log", [1L: "RESPONSE ok"])], true),
                      new StepInputs.Input("errors", [file("errors.log", [1L: "ERROR x"])], false)]

        when:
        String text = StepInputs.filesText(inputs, tools(), true)

        then:
        text.contains('=== Step "transfers" - the files it gave follow; it was CUT at its limit')
        text.contains('=== Step "errors" - the files it gave follow ===')
    }

    def "for a model without tools a file too large is cut - and what depends on its rest must be called unclear"() {
        given: "a log far larger than goes with the question"
        def lines = (1..2000).collectEntries { [(it as long): "ERROR " + "x" * 80] }
        def inputs = [new StepInputs.Input("failures", [file("app.log", lines), file("other.log", [1L: "WARN y"])], false)]
        def tools = tools()

        when:
        String text = StepInputs.filesText(inputs, tools, false)

        then: "its first lines go, the rest is marked as judged by nobody - the file after it too"
        text.length() <= StepInputs.INLINE_CHARACTERS + 2000
        text.contains("1| ERROR")
        !text.contains("2000| ERROR")
        text.count("CUT: the rest could not be given and nobody judges it") == 2
        text.contains("call what depends on it unclear")
        tools.exploredFiles().isEmpty()
    }

    def "a large file two steps gave is one file for search_file and read_file_lines, with the lines of both"() {
        given: "the same log split by two filters, each part too large to go with the question"
        def errors = (1..1000).collectEntries { [(it * 2L - 1): "ERROR " + "x" * 80] }
        def warnings = (1..1000).collectEntries { [(it * 2L): "WARN " + "y" * 80] }
        def inputs = [new StepInputs.Input("failures", [file("app.log", errors)], false),
                      new StepInputs.Input("warnings", [file("app.log", warnings)], false)]
        def tools = tools()

        when:
        StepInputs.filesText(inputs, tools, true)

        then:
        tools.exploredFiles().size() == 1
        def lines = tools.exploredFiles()[0].lines()
        lines.size() == 2000
        lines[0].startsWith("1| ERROR")
        lines[1].startsWith("2| WARN")
    }

    private static RunFile file(String name, Map<Long, String> lines) {
        new RunFile(1L, name, lines.collect { new RunFile.Line(it.key, it.value) })
    }

    private static ToolContext tools() {
        new ToolContext(null, ToolContext.Asker.TASK, null, null, null, null, null, false, 0, ToolContext.Files.NONE)
    }
}
