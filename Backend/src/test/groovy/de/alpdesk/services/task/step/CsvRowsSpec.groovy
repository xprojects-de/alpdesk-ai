package de.alpdesk.services.task.step

import de.alpdesk.services.task.TaskDefinition.Field
import de.alpdesk.services.task.TaskDefinition.FieldType
import spock.lang.Specification

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The CSV of a write_file step: written and read back alike, values in the task's dialect, no formulas. */
class CsvRowsSpec extends Specification {

    def "a file is written with a BOM and \\r\\n, values quoted only when they must be - and read back the same"() {
        given:
        def header = ["partner", "note"]
        def rows = [["Musterfirma; Zweigstelle", 'sagt "hallo"'], ["Beispiel GmbH", "zwei\nZeilen"], ["", ""]]

        when:
        def text = CsvRows.format(header, rows, ';' as char)
        def table = CsvRows.parse(text, ';' as char)

        then:
        text.startsWith("﻿partner;note\r\n")
        text.contains('"Musterfirma; Zweigstelle";"sagt ""hallo"""\r\n')
        table.header() == header
        table.rows() == rows
    }

    def "a value is written by its field's type: #type #value as #cell"() {
        expect:
        CsvRows.cell(new Field("a", FieldType.valueOf(type), false, null, []), value, decimal as char,
                DateTimeFormatter.ofPattern("dd.MM.yyyy")) == cell

        where:
        type      | value              | decimal || cell
        "AMOUNT"  | 119                | ","     || "119,00"
        "AMOUNT"  | 636.65             | ","     || "636,65"
        "AMOUNT"  | 636.65             | "."     || "636.65"
        "AMOUNT"  | -12.5              | ","     || "-12,50"
        "NUMBER"  | new BigDecimal("1E+2") | "," || "100"
        "PERCENT" | 19                 | ","     || "19"
        "PERCENT" | 7.5                | ","     || "7,5"
        "DATE"    | "2026-01-24"       | ","     || "24.01.2026"
        "TEXT"    | "Musterfirma"      | ","     || "Musterfirma"
        "TEXT"    | "=HYPERLINK(\"x\")" | ","    || "'=HYPERLINK(\"x\")"
        "TEXT"    | "+49 30 1234"      | ","     || "'+49 30 1234"
        "TEXT"    | "@SUMME(A1)"       | ","     || "'@SUMME(A1)"
        "TEXT"    | "-1"               | ","     || "'-1"
        "TEXT"    | "\t=1+1"           | ","     || "'\t=1+1"
        "TEXT"    | "\r=1+1"           | ","     || "'\r=1+1"
        "CHOICE"  | "invoice"          | ","     || "invoice"
        "TEXT"    | null               | ","     || ""
    }

    def "a file's name takes the run's date, a row's date and a row's choice - the run's date or 'none' where the row has none"() {
        given:
        def today = LocalDate.parse("2027-01-05")

        expect:
        WriteFileStep.fileOf(file, values, today) == expected

        where:
        file                                  | values                                 || expected
        "Export/{yyyy}/rows.csv"              | [:]                                    || "Export/2027/rows.csv"
        "Export/{date:yyyy}/rows.csv"         | [date: "2026-12-14"]                   || "Export/2026/rows.csv"
        "Export/{date:yyyy}-{date:MM}.csv"    | [date: "2026-12-14"]                   || "Export/2026-12.csv"
        "Export/{date:yyyy}/rows.csv"         | [:]                                    || "Export/2027/rows.csv"
        "Export/{kind}.csv"                   | [kind: "credit_note"]                  || "Export/credit_note.csv"
        "Export/{kind}.csv"                   | [:]                                    || "Export/none.csv"
        "Export/{yyyy}-{MM}-{dd}.csv"         | [:]                                    || "Export/2027-01-05.csv"
    }
}
