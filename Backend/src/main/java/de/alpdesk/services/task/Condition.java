package de.alpdesk.services.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A condition on the counts of earlier steps' results - "verify.errors > 0 || verify.warnings > 0" - or on the word a
 * prompt step's model picked from its fixed choices ("review.choice == rework"), never on a model's free text.
 * Comparisons joined by && and ||, && binding first; no brackets, no functions: the format stays data.
 *
 * @param anyOf true when one of its groups holds, a group when all its comparisons hold
 */
@Serdeable
public record Condition(String text, List<List<Comparison>> anyOf) {

    private static final Pattern COMPARISON = Pattern.compile("([a-z][a-z0-9_]*)\\.([a-z]+)\\s*(>=|<=|==|!=|>|<)\\s*(\\d{1,9})");
    private static final Pattern CHOICE = Pattern.compile("([a-z][a-z0-9_]*)\\.choice\\s*(==|!=)\\s*([a-z0-9_-]{1,40})");

    /** @param word for a choice ("choice" as count): the word compared with; null for a count */
    @Serdeable
    public record Comparison(String step, String count, String operator, long value, @Nullable String word) {

        boolean holds(Map<String, Map<String, Object>> results) {
            Map<String, Object> result = results.get(step);
            if (word != null) {
                boolean picked = result != null && word.equals(result.get(TaskResults.CHOICE));
                return operator.equals("==") == picked;
            }
            long actual = result != null && result.get(count) instanceof Number number ? number.longValue() : 0;
            return switch (operator) {
                case ">" -> actual > value;
                case ">=" -> actual >= value;
                case "<" -> actual < value;
                case "<=" -> actual <= value;
                case "==" -> actual == value;
                default -> actual != value;
            };
        }
    }

    /** Null when the text is not such a condition. */
    public static @Nullable Condition parse(String text) {
        List<List<Comparison>> groups = new ArrayList<>();
        for (String group : text.split("\\|\\|", -1)) {
            List<Comparison> comparisons = new ArrayList<>();
            for (String part : group.split("&&", -1)) {
                Matcher choice = CHOICE.matcher(part.strip());
                if (choice.matches()) {
                    comparisons.add(new Comparison(choice.group(1), TaskResults.CHOICE, choice.group(2), 0,
                            choice.group(3)));
                    continue;
                }
                Matcher match = COMPARISON.matcher(part.strip());
                if (!match.matches()) {
                    return null;
                }
                comparisons.add(new Comparison(match.group(1), match.group(2), match.group(3),
                        Long.parseLong(match.group(4)), null));
            }
            groups.add(comparisons);
        }
        return new Condition(text.strip(), groups);
    }

    /** A step that gave no result (skipped, not reached) counts 0 everywhere. */
    public boolean holds(Map<String, Map<String, Object>> results) {
        for (List<Comparison> group : anyOf) {
            if (group.stream().allMatch(comparison -> comparison.holds(results))) {
                return true;
            }
        }
        return false;
    }
}
