package de.alpdesk.services.task.step;

import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.task.TaskDefinition.Mask;
import io.micronaut.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read_files step's masks (docs/architecture/tasks.md "Steps"), applied to a line before it is kept: nothing masked
 * reaches the run file, the model or the mail. "after" replaces the value after a text (spaces between them skipped)
 * up to the next separator, so a password needs no regular expression - also in HTML-escaped logs, where &amp; ends
 * it. A regular expression gets
 * a time limit per line: Java's matcher can take practically for ever on an unlucky one - on Java 25 no longer on
 * (a+)+$, but still with a backreference, e.g. (a+)+\1$ (measured 24 October 2026: over 3 s on 31 characters).
 */
final class LineMasks {

    static final String MASKED = "***";
    static final long REGEX_MILLIS = 100;
    private static final String SEPARATORS = "\"'&<>,; \t";

    private final List<String> afters = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();

    LineMasks(List<Mask> masks) {
        for (Mask mask : masks) {
            if (mask.after() != null) {
                afters.add(mask.after());
            } else if (mask.regex() != null) {
                patterns.add(Pattern.compile(mask.regex()));
            }
        }
    }

    String apply(String line) {
        String masked = line;
        for (String after : afters) {
            masked = maskAfter(masked, after);
        }
        for (Pattern pattern : patterns) {
            masked = maskMatches(masked, pattern);
        }
        return masked;
    }

    private static String maskAfter(String line, String after) {
        StringBuilder masked = new StringBuilder();
        int from = 0;
        int found = line.indexOf(after);
        while (found >= 0) {
            // "password: s3cret": the task's text loses the space after "password:" (fields are stripped), and a
            // space is a separator - without skipping it the value would be empty and the password kept.
            int start = skipBlanks(line, found + after.length());
            int end = start;
            while (end < line.length() && SEPARATORS.indexOf(line.charAt(end)) < 0) {
                end++;
            }
            masked.append(line, from, start);
            if (end > start) {
                masked.append(MASKED);
            }
            from = end;
            found = line.indexOf(after, end);
        }
        return masked.append(line, from, line.length()).toString();
    }

    private static int skipBlanks(String line, int at) {
        int index = at;
        while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private static String maskMatches(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(new Deadline(line, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGEX_MILLIS)));
        try {
            return matcher.replaceAll(MASKED);
        } catch (TooSlow e) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "task.maskTooSlow", "The mask's regular expression "
                    + pattern.pattern() + " took longer than " + REGEX_MILLIS + " ms on a line - make it simpler",
                    Map.of("regex", pattern.pattern(), "millis", String.valueOf(REGEX_MILLIS)));
        }
    }

    /** The line as the matcher reads it - past the deadline every read throws, which ends the match. */
    private static final class Deadline implements CharSequence {
        private final CharSequence text;
        private final long until;
        private int reads;

        Deadline(CharSequence text, long until) {
            this.text = text;
            this.until = until;
        }

        @Override
        public char charAt(int index) {
            if (++reads % 1024 == 0 && System.nanoTime() > until) {
                throw new TooSlow();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.subSequence(start, end), until);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static final class TooSlow extends RuntimeException {
        TooSlow() {
            super(null, null, false, false);
        }
    }
}
