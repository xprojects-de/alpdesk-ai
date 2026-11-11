package de.alpdesk.services.task.step;

import de.alpdesk.services.task.TaskDefinition.Field;
import io.micronaut.core.annotation.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The CSV a write_file step keeps (docs/architecture/tasks.md "write_file"): UTF-8 with a BOM (a spreadsheet opens it
 * with its umlauts right), lines ending in \r\n, a value quoted when it holds the separator, a quote or a line break.
 * Values are written by their field's type in the task's dialect - an amount with two decimals and the task's decimal
 * separator, a date in its format - and a text beginning like a formula gets a leading ' (CSV injection: the documents
 * come from outside, a supplier's PDF must not put a formula into the company's spreadsheet).
 */
final class CsvRows {

    static final String BOM = "﻿";

    private CsvRows() {
    }

    /** A CSV read back: its header and its rows, each as many values as it has. */
    record Table(List<String> header, List<List<String>> rows) {
    }

    /** The file's text as written - quotes, separators and line breaks inside quotes kept in the value. */
    static Table parse(String text, char separator) {
        String content = text.startsWith(BOM) ? text.substring(1) : text;
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    value.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == separator) {
                line.add(value.toString());
                value.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || !value.isEmpty()) {
                    line.add(value.toString());
                    lines.add(line);
                }
                line = new ArrayList<>();
                value.setLength(0);
                any = false;
            } else {
                value.append(c);
                any = true;
            }
        }
        if (any || !value.isEmpty()) {
            line.add(value.toString());
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return new Table(List.of(), List.of());
        }
        return new Table(lines.getFirst(), lines.subList(1, lines.size()));
    }

    /** The whole file: the BOM, the header and the rows, each line ending in \r\n. */
    static String format(List<String> header, List<List<String>> rows, char separator) {
        StringBuilder text = new StringBuilder(BOM);
        line(text, header, separator);
        for (List<String> row : rows) {
            line(text, row, separator);
        }
        return text.toString();
    }

    private static void line(StringBuilder text, List<String> values, char separator) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(separator);
            }
            text.append(quoted(values.get(i), separator));
        }
        text.append("\r\n");
    }

    private static String quoted(String value, char separator) {
        if (value.indexOf(separator) < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * A value as the row's cell: typed fields in the task's dialect, a text or choice guarded against formulas; null
     * an empty cell.
     */
    static String cell(Field field, @Nullable Object value, char decimal, DateTimeFormatter dates) {
        if (value == null) {
            return "";
        }
        return switch (field.type()) {
            case AMOUNT -> number(value, decimal, 2);
            case NUMBER, PERCENT -> number(value, decimal, -1);
            case DATE -> date(String.valueOf(value), dates);
            case TEXT, CHOICE -> guarded(String.valueOf(value));
        };
    }

    /** A text a spreadsheet would take for a formula - =, +, -, @, a tab or a carriage return first - gets a '. */
    static String guarded(String text) {
        if (text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + text : text;
    }

    /** A number in plain digits (never 1E+2), the task's decimal separator; scale fixed for an amount. */
    private static String number(Object value, char decimal, int scale) {
        BigDecimal number;
        try {
            number = new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return guarded(String.valueOf(value));
        }
        if (scale >= 0) {
            number = number.setScale(scale, RoundingMode.HALF_UP);
        }
        return number.toPlainString().replace('.', decimal);
    }

    private static String date(String iso, DateTimeFormatter dates) {
        try {
            return LocalDate.parse(iso).format(dates);
        } catch (DateTimeParseException e) {
            return guarded(iso);
        }
    }
}
