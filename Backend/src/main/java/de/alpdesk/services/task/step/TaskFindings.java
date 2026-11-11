package de.alpdesk.services.task.step;

import de.alpdesk.entities.task.RunFile;
import de.alpdesk.services.task.TaskDefinition.CheckPoint;
import de.alpdesk.services.task.TaskDefinition.Severity;
import de.alpdesk.services.task.TaskResults;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the model's last answer as the structured result of an agent or verify step: every check point exactly once
 * with ok, finding or unclear - and every evidence line one that is really in the run's files. A line the model made up
 * is dropped; a finding left without evidence counts as unclear, never as an error found.
 */
final class TaskFindings {

    static final String STATUS_OK = "ok";
    static final String STATUS_FINDING = "finding";
    static final String STATUS_UNCLEAR = "unclear";
    private static final int MAX_EVIDENCE = 20;
    private static final int MAX_NOTE = 1000;
    /** A report's note is the report itself - every group of what it lists (a real one ran past 1000, 24 October 2026). */
    private static final int MAX_REPORT_NOTE = 6000;

    /** The answer is not the result asked for - the model gets one more round with the problem. */
    static final class Invalid extends Exception {
        Invalid(String problem) {
            super(problem);
        }
    }

    private TaskFindings() {
    }

    static Map<String, Object> read(String answer, List<CheckPoint> checks, List<RunFile> files, JsonMapper json)
            throws Invalid {
        Map<String, Object> parsed = parse(answer, json);
        if (!(parsed.get("checks") instanceof List<?> reported)) {
            throw new Invalid("there is no list \"checks\"");
        }
        Map<String, Map<?, ?>> byName = new HashMap<>();
        for (Object item : reported) {
            if (item instanceof Map<?, ?> check && check.get("name") instanceof String name) {
                if (byName.put(name.strip().toLowerCase(Locale.ROOT), check) != null) {
                    throw new Invalid("the check point \"" + name + "\" appears twice");
                }
            }
        }
        Map<String, Map<Long, String>> lines = lines(files);
        List<Map<String, Object>> results = new ArrayList<>();
        for (CheckPoint check : checks) {
            Map<?, ?> found = byName.get(check.name().toLowerCase(Locale.ROOT));
            if (found == null) {
                throw new Invalid("the check point \"" + check.name() + "\" is missing");
            }
            results.add(result(check, found, lines));
        }
        return counted(results);
    }

    /** Every check point ok without asking a model: there was nothing new to read. */
    static Map<String, Object> nothingToCheck(List<CheckPoint> checks) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (CheckPoint check : checks) {
            results.add(check(check, STATUS_OK, "No new lines to check.", List.of()));
        }
        return counted(results);
    }

    static Map<String, Object> counted(List<Map<String, Object>> checks) {
        int errors = 0;
        int warnings = 0;
        int reports = 0;
        int unclear = 0;
        int ok = 0;
        for (Map<String, Object> check : checks) {
            switch ((String) check.get("status")) {
                case STATUS_FINDING -> {
                    switch (Severity.valueOf((String) check.get("severity"))) {
                        case ERROR -> errors++;
                        case WARNING -> warnings++;
                        case REPORT -> reports++;
                    }
                }
                case STATUS_UNCLEAR -> unclear++;
                default -> ok++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.ERRORS, errors);
        result.put(TaskResults.WARNINGS, warnings);
        result.put(TaskResults.REPORTS, reports);
        result.put(TaskResults.UNCLEAR, unclear);
        result.put(TaskResults.OK, ok);
        result.put(TaskResults.CHECKS, checks);
        return result;
    }

    /** The report check points among these results. */
    static List<Map<String, Object>> reports(List<Map<String, Object>> checks) {
        return checks.stream().filter(TaskFindings::isReport).toList();
    }

    static List<Map<String, Object>> withoutReports(List<Map<String, Object>> checks) {
        return checks.stream().filter(check -> !isReport(check)).toList();
    }

    /** A verification's result with the reports it handed on, in the order of the task's check points. */
    static Map<String, Object> withReports(Map<String, Object> judged, List<Map<String, Object>> reports,
                                           List<CheckPoint> order) {
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> check : checks(judged)) {
            byName.put((String) check.get("name"), check);
        }
        for (Map<String, Object> report : reports) {
            byName.put((String) report.get("name"), report);
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        for (CheckPoint check : order) {
            if (byName.containsKey(check.name())) {
                merged.add(byName.get(check.name()));
            }
        }
        return counted(merged);
    }

    private static boolean isReport(Map<String, Object> check) {
        return Severity.REPORT.name().equals(check.get("severity"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> checks(@Nullable Map<String, Object> result) {
        return result != null && result.get(TaskResults.CHECKS) instanceof List<?> checks
                ? (List<Map<String, Object>>) checks : List.of();
    }

    private static Map<String, Object> result(CheckPoint check, Map<?, ?> found, Map<String, Map<Long, String>> lines)
            throws Invalid {
        String status = found.get("status") instanceof String text ? text.strip().toLowerCase(Locale.ROOT) : "";
        if (!List.of(STATUS_OK, STATUS_FINDING, STATUS_UNCLEAR).contains(status)) {
            throw new Invalid("the status of \"" + check.name() + "\" is ok, finding or unclear");
        }
        String note = found.get("note") instanceof String text ? text.strip() : "";
        int maxNote = check.severity() == Severity.REPORT ? MAX_REPORT_NOTE : MAX_NOTE;
        if (note.length() > maxNote) {
            note = note.substring(0, maxNote) + " [...]";
        }
        List<Map<String, Object>> evidence = evidence(found.get("evidence"), lines);
        if (status.equals(STATUS_FINDING) && evidence.isEmpty()) {
            return check(check, STATUS_UNCLEAR, note + " (no line of the files shows it)", List.of());
        }
        return check(check, status, note, evidence);
    }

    /** The lines named that are in the files, with their text - at most MAX_EVIDENCE. */
    private static List<Map<String, Object>> evidence(@Nullable Object named, Map<String, Map<Long, String>> lines) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (!(named instanceof List<?> list)) {
            return evidence;
        }
        for (Object item : list) {
            if (evidence.size() >= MAX_EVIDENCE || !(item instanceof Map<?, ?> line)) {
                continue;
            }
            String file = line.get("file") instanceof String name ? name.strip() : null;
            Long number = line.get("line") instanceof Number value ? value.longValue() : null;
            Map<Long, String> fileLines = file == null ? null : lines.get(file.toLowerCase(Locale.ROOT));
            String text = fileLines == null || number == null ? null : fileLines.get(number);
            if (text != null) {
                Map<String, Object> found = new LinkedHashMap<>();
                found.put("file", file);
                found.put("line", number);
                found.put("text", text);
                evidence.add(found);
            }
        }
        return evidence;
    }

    private static Map<String, Object> check(CheckPoint check, String status, String note,
                                             List<Map<String, Object>> evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", check.name());
        result.put("severity", check.severity().name());
        result.put("status", status);
        result.put("note", note);
        result.put("evidence", evidence);
        return result;
    }

    private static Map<String, Map<Long, String>> lines(List<RunFile> files) {
        Map<String, Map<Long, String>> lines = new HashMap<>();
        for (RunFile file : files) {
            Map<Long, String> byNumber = lines.computeIfAbsent(file.name().toLowerCase(Locale.ROOT), _ -> new HashMap<>());
            for (RunFile.Line line : file.lines()) {
                byNumber.put(line.number(), line.text());
            }
        }
        return lines;
    }

    /** The JSON object in the answer - models like to put it into a code block. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String answer, JsonMapper json) throws Invalid {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
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
