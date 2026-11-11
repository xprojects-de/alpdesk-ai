package de.alpdesk.services.task.step

import de.alpdesk.services.task.TaskDefinition.Field
import de.alpdesk.services.task.TaskDefinition.FieldType
import io.micronaut.json.JsonMapper
import spock.lang.Shared
import spock.lang.Specification

/** An extract step's answer checked against its fields: what passes is kept typed, what does not is named. */
class ExtractedRowSpec extends Specification {

    @Shared
    JsonMapper json = JsonMapper.createDefault()

    def "a #type value #given is kept as #kept"() {
        given:
        def field = new Field("a", FieldType.valueOf(type.toUpperCase()), false, null, values)

        when:
        def checked = ExtractedRow.read('{"values": {"a": ' + given + '}, "unclear": null}', [field], json)

        then:
        checked.values().a == kept
        checked.wrong().isEmpty() == (kept != null || given == "null" || given == '""')

        where:
        type      | values           | given            || kept
        "text"    | []               | '"Musterfirma"'  || "Musterfirma"
        "text"    | []               | '1001'           || "1001"
        "text"    | []               | '""'             || null
        "date"    | []               | '"2026-01-24"'   || "2026-01-24"
        "date"    | []               | '"24.01.2026"'   || null
        "amount"  | []               | '119.00'         || 119
        "amount"  | []               | '"636.65"'       || 636.65
        "amount"  | []               | '"636,65"'       || null
        "amount"  | []               | '-12.5'          || -12.5
        "amount"  | []               | '100.00'         || 100
        "amount"  | []               | '"1.5e3"'        || 1500
        "amount"  | []               | '"1e99999999"'   || null
        "amount"  | []               | '1e99999999'     || null
        "number"  | []               | '3'              || 3
        "percent" | []               | '19'             || 19
        "percent" | []               | '119'            || null
        "percent" | []               | '-1'             || null
        "choice"  | ["invoice", "x"] | '"invoice"'      || "invoice"
        "choice"  | ["invoice", "x"] | '"Rechnung"'     || null
        "date"    | []               | 'null'           || null
    }

    def "a required field missing is named; a value the fields do not know is left out; the model's unclear is kept"() {
        when:
        def checked = ExtractedRow.read('```json\n{"values": {"b": "x", "c": "y"}, "unclear": "zwei Steuersätze"}\n```',
                [new Field("a", FieldType.AMOUNT, true, null, []), new Field("b", FieldType.TEXT, false, null, [])], json)

        then:
        checked.values() == [b: "x"]
        checked.wrong() == ["a (required, but missing)"]
        checked.unclear() == "zwei Steuersätze"
        !checked.valid()
    }

    def "an answer without values is no result: #answer"() {
        when:
        ExtractedRow.read(answer, [new Field("a", FieldType.TEXT, false, null, [])], json)

        then:
        thrown(ExtractedRow.Invalid)

        where:
        answer << ["Ich habe alles gelesen.", '{"rows": []}', '{"values": [1]}', "{kaputt"]
    }
}
