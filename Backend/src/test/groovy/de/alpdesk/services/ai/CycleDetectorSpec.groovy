package de.alpdesk.services.ai

import spock.lang.Specification

/**
 * A loop is the same block of lines, word for word, five times in a row at the end of the text - also a block of
 * several different paragraphs; a model replanning in other words or fewer copies are none, a short line repeated
 * only once its copies make five blocks of 200 characters.
 */
class CycleDetectorSpec extends Specification {

    static final String PREAMBLE = """Die Frage betrifft die Konfiguration des Kaskadenmoduls.
Ich suche zuerst in den Dokumenten der Firma nach dem Modul und seiner Busadresse.
"""
    static final String BLOCK_A = """Okay.
Let's write.
The answer will be in German.
I will use the `KM4` class name.

One detail: the manual says "four boilers at most, leading boiler first".
I'll mention that.

"""
    static final String BLOCK_B = """Okay.
Let's go.
The user asked "welches Modul".
Answer: KM-4 for up to four boilers, or two KM-4 for more.
I'll focus on KM-4.

"""
    static final String BLOCK_C = """Okay.
Ready.
The configuration needs busAddress and deviceCount, the rest is optional.
I'll show the template from the manual.

"""
    static final String BLOCK_D = """Okay.
One more check.
The leading boiler gets address 1, every other one the next number.
That is in the manual too, I'll add it.

"""

    def "a loop of #blocks different paragraphs is found with the fifth copy - not before: its period is all of them"() {
        given:
        def detector = new CycleDetector()
        def cycle = blocks.collect { [BLOCK_A, BLOCK_B, BLOCK_C, BLOCK_D][it] }.join("")

        expect: "four copies are no loop yet"
        feed(detector, PREAMBLE + cycle * 4) == null

        when:
        def loop = feed(detector, cycle)

        then:
        loop == new CycleDetector.Cycle(cycle.count("\n"), cycle.length(), 5)

        where:
        blocks << [[0, 1], [0, 1, 2], [0, 1, 2, 3]]
    }

    def "the pieces may cut the text anywhere - as a model streams it"() {
        given:
        def detector = new CycleDetector()
        def text = PREAMBLE + (BLOCK_A + BLOCK_B) * 6
        def random = new Random(7)
        def found = null
        int at = 0

        when:
        while (at < text.length() && found == null) {
            int size = 1 + random.nextInt(12)
            found = detector.add(text.substring(at, Math.min(text.length(), at + size)))
            at += size
        }

        then:
        found == new CycleDetector.Cycle((BLOCK_A + BLOCK_B).count("\n"), (BLOCK_A + BLOCK_B).length(), 5)
        at < text.length()
    }

    def "a short line repeated is no loop until its copies make five blocks of 200 characters"() {
        expect: "\"Okay.\" 100 times: 600 characters"
        feed(new CycleDetector(), "Okay.\n" * 100) == null

        and: "200 times: five blocks of 34 lines, 204 characters each"
        feed(new CycleDetector(), "Okay.\n" * 200) == new CycleDetector.Cycle(34, 204, 5)

        and: "empty lines never - they are not even 200 characters in all the lines kept"
        feed(new CycleDetector(), "\n" * 1000) == null
    }

    def "a model replanning in other words each time is no loop"() {
        given: "attempts like a model's that hesitates before its tool call - never word for word"
        def attempts = (1..20).collect { i ->
            """Let's do the search now (attempt ${i}).
*Self-Correction:* I should search for "KM-4 Konfiguration" ${i % 2 == 0 ? "first" : "and the manual"}.
Actually, I'll run ${i} queries: "KM-4", "Kaskade", "busAddress".
"""
        }

        expect:
        feed(new CycleDetector(), attempts.join("")) == null
    }

    def "a block repeated with one value counting up is no loop - only word for word is"() {
        given:
        def text = (1..10).collect { i -> BLOCK_A.replace("four boilers", "${i} boilers") }.join("")

        expect:
        feed(new CycleDetector(), text) == null
    }

    def "a loop without line breaks is found too - later, once its length meets the cut at #CycleDetector.MAX_UNIT"() {
        given: "450 characters in one line, again and again"
        def sentence = ("Ich schreibe jetzt die Antwort zum Kaskadenmodul KM-4 auf Deutsch. " * 7).substring(0, 450)
        def detector = new CycleDetector()

        when:
        def loop = feed(detector, sentence * 50)

        then:
        loop != null
        loop.chars() % 450 == 0
    }

    def "a loop longer than what is kept is not found - the hashes of the oldest lines are gone"() {
        given: "a block of 120 different lines: five copies are more than the 512 lines kept"
        def block = (1..120).collect { "Zeile ${it} des Gedankens zum Kaskadenmodul.\n" }.join("")

        expect:
        feed(new CycleDetector(), block * 6) == null
    }

    def "code does not count: the same configuration drafted for five devices is no loop - #what"() {
        expect:
        feed(new CycleDetector(), PREAMBLE + text) == null

        where:
        what                                     | text
        "XML blocks"                             | XML * 5
        "XML blocks with a short sentence between" | ("Dann das nächste Gerät, derselbe Block:\n" + XML) * 5
        "an XML line longer than a cut"          | ("<param name=\"description\" value=\"" + "x" * 900 + "\" />\n") * 5
        "a fenced block"                         | ("```xml\n" + XML + "```\n") * 5
        "a fenced block of prose"                | ("```\n" + BLOCK_A + "```\n") * 5
        "table rows"                             | "| busAddress | 12 | the address on the bus, set with the rotary switch on the front of the module |\n" * 40
    }

    def "a long sentence repeated word for word between code blocks is prose - and a loop"() {
        given: "the same 200 characters of prose before each of five identical blocks"
        def sentence = ("Jetzt schreibe ich den Block für das nächste Gerät, genau wie beim vorigen, mit derselben Adresse. " * 3).substring(0, 220) + "\n"

        expect:
        feed(new CycleDetector(), PREAMBLE + (sentence + XML) * 5) == new CycleDetector.Cycle(1, sentence.length(), 5)
    }

    def "prose repeated around code is still a loop - the code is left out, the prose counted"() {
        given:
        def cycle = BLOCK_A + XML + BLOCK_B

        expect:
        feed(new CycleDetector(), PREAMBLE + cycle * 5) ==
                new CycleDetector.Cycle((BLOCK_A + BLOCK_B).count("\n"), (BLOCK_A + BLOCK_B).length(), 5)
    }

    def "a fence never closed hides the rest - no loop is reported in it"() {
        expect:
        feed(new CycleDetector(), PREAMBLE + "```\n" + (BLOCK_A + BLOCK_B) * 8) == null
    }

    static final String XML = """<object name="kessel" className="BoilerChannel">
  <param name="deviceAddress" value="1" />
  <param name="maxFlowTemperature" value="75.0" />
</object>
"""

    /** Feeds the text line by line: the loop the detector reports, or null. */
    private static CycleDetector.Cycle feed(CycleDetector detector, String text) {
        def found = null
        text.split("(?<=\n)").each { line ->
            if (found == null) {
                found = detector.add(line)
            }
        }
        found
    }
}
