package de.alpdesk.services.task.step;

import de.alpdesk.services.task.TaskDefinition.Field;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An extract step's answer for one document, checked against the step's fields (docs/architecture/tasks.md
 * "extract"): {"values": {...}, "unclear": "<why>" or null}. Every value is checked by its field's type - a date ISO, an
 * amount, number or percent a number with "." as its decimal separator, a choice one of its words, a text at most
 * {@value #MAX_TEXT} characters - and a required field must be there. What passes is kept; what does not is named.
 * A value the fields do not know is left out.
 */
final class ExtractedRow {

    static final int MAX_TEXT = 1000;

    private ExtractedRow() {
    }

    /**
     * @param values the values that passed, by the fields' names - typed: a date as its ISO text, numbers as decimals
     * @param wrong  each field that did not pass, with why
     * @param unclear what the model said it could not settle; null: nothing
     */
    record Checked(Map<String, Object> values, List<String> wrong, @Nullable String unclear) {

        boolean valid() {
            return wrong.isEmpty();
        }
    }

    /** The answer holds no JSON object with values - nothing of it can be kept. */
    static final class Invalid extends Exception {
        Invalid(String problem) {
            super(problem);
        }
    }

    static Checked read(String answer, List<Field> fields, JsonMapper json) throws Invalid {
        Map<String, Object> parsed = parse(answer, json);
        if (!(parsed.get("values") instanceof Map<?, ?> given)) {
            throw new Invalid("it has no object \"values\"");
        }
        return check(given, parsed.get("unclear"), fields);
    }

    /** The values given - a model's, or the properties of an object a tool answered with - checked by the fields. */
    static Checked check(Map<?, ?> given, @Nullable Object unclear, List<Field> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        for (Field field : fields) {
            Object value = given.get(field.name());
            if (value == null || value instanceof String text && text.isBlank()) {
                if (field.required()) {
                    wrong.add(field.name() + " (required, but missing)");
                }
                continue;
            }
            Object typed = typed(field, value);
            if (typed == null) {
                wrong.add(field.name() + " (" + expected(field) + ")");
            } else {
                values.put(field.name(), typed);
            }
        }
        String reason = unclear instanceof String text && !text.isBlank() ? text.strip() : null;
        return new Checked(values, wrong, reason);
    }

    /** The value as its field's type, or null when it is not one. */
    private static @Nullable Object typed(Field field, Object value) {
        return switch (field.type()) {
            case TEXT -> value instanceof String || value instanceof Number ? text(String.valueOf(value)) : null;
            case DATE -> value instanceof String text ? date(text.strip()) : null;
            case AMOUNT, NUMBER -> decimal(value);
            case PERCENT -> {
                BigDecimal percent = decimal(value);
                yield percent != null && percent.signum() >= 0 && percent.compareTo(BigDecimal.valueOf(100)) <= 0
                        ? percent : null;
            }
            case CHOICE -> value instanceof String text && field.values().contains(text.strip()) ? text.strip() : null;
        };
    }

    private static @Nullable String text(String text) {
        String stripped = text.strip();
        return stripped.length() <= MAX_TEXT ? stripped : null;
    }

    private static @Nullable String date(String text) {
        try {
            return LocalDate.parse(text).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * A JSON number, or a text that is one with "." as its separator - "119,00" is refused, not guessed. The exponent
     * has two digits at most: "1e99999999" would be a number of a hundred million digits once written out.
     */
    private static @Nullable BigDecimal decimal(Object value) {
        String text = value instanceof Number || value instanceof String ? String.valueOf(value).strip() : null;
        if (text == null || !text.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d{1,2})?")) {
            return null;
        }
        // 119.00 is kept as 119, but 100 not as 1E+2 - what the run view, the API and a CSV write
        BigDecimal decimal = new BigDecimal(text).stripTrailingZeros();
        return decimal.scale() < 0 ? decimal.setScale(0) : decimal;
    }

    private static String expected(Field field) {
        return switch (field.type()) {
            case TEXT -> "a text of at most " + MAX_TEXT + " characters";
            case DATE -> "a date as YYYY-MM-DD";
            case AMOUNT, NUMBER -> "a number with . as the decimal separator";
            case PERCENT -> "a number from 0 to 100";
            case CHOICE -> "one of " + String.join(", ", field.values());
        };
    }

    /** The JSON object in the answer - models like to put it into a code block. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String answer, JsonMapper json) throws Invalid {
        int start = answer == null ? -1 : answer.indexOf('{');
        int end = answer == null ? -1 : answer.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new Invalid("it holds no JSON object");
        }
        try {
            return json.readValue(answer.substring(start, end + 1), Map.class);
        } catch (IOException | RuntimeException e) {
            throw new Invalid("it is no valid JSON");
        }
    }
}
