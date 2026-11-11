package de.alpdesk.services.task.step;

import de.alpdesk.entities.task.RunFile;
import de.alpdesk.services.task.TaskDefinition.CheckPoint;
import de.alpdesk.services.task.TaskDefinition.Field;
import de.alpdesk.services.task.TaskDefinition.Severity;
import de.alpdesk.services.task.TaskDefinition.StepType;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the model of an agent or verify step is told (docs/architecture/tasks.md "Steps"). The lines go with their
 * number in the file before a "|", so a finding names a line the run can check - and the mail can quote.
 */
final class TaskPrompts {

    /** A prompt step answered by the tenant's assistant (assistant: true). */
    static final String ASSISTANT = """
            You answer as the company's assistant: search the company's documents with similarity_search for what the
            step needs and read on with read_document; tools whose names start with a software's name reach the
            company's software - use them for its current records. Name the documents and software you drew on.""";

    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    static final String AGENT_SYSTEM = "You check files for a scheduled task of a company. Answer only from the lines "
            + "you are given or read with the tools - never from what you assume. For every check point decide: "
            + "\"finding\" when the lines show it, \"ok\" when they show it did not happen or nothing points to it, "
            + "\"unclear\" when you cannot tell. ";

    static final String VERIFY_SYSTEM = "You verify the findings of another check of a company's scheduled task against "
            + "the files. Look at the lines of every finding and at the lines around them and in the other files: "
            + "\"finding\" when the lines really show it, \"ok\" when the finding does not hold, \"unclear\" when you "
            + "cannot tell even with the files. The mail shows your note under the finding, so your note states the "
            + "problem itself - what the lines show is wrong, where and how often; with \"ok\" why "
            + "the finding does not hold - never that you checked it or that the lines match. ";

    // At most 20 evidence lines are kept - told, since a real model listed hundreds for a report over 2000 lines and its
    // answer ran past the provider's output limit, cut in the middle of the JSON (24 October 2026)
    static final String FORM = "Every finding names the lines that show it: the file's name and the number before the "
            + "| of a line - at most 20 per check point, the most telling ones (more are not kept). Your last answer is "
            + "only this JSON object, nothing before or after it:\n"
            + "{\"checks\": [{\"name\": \"<check point>\", \"status\": \"ok|finding|unclear\", "
            + "\"note\": \"<one or two sentences - a report: the report itself>\", "
            + "\"evidence\": [{\"file\": \"<file name>\", \"line\": <number>}]}]}\n"
            + "Every check point appears exactly once, by its name.";

    static final String NOTES = "=== Notes of the verification of your last findings ===";
    static final String SENT_BACK = "=== Why you are asked again - the answer that sent you back";

    static final String PROMPT_SYSTEM = "You are one step of a scheduled task of a company. Do what the prompt asks, "
            + "from what you are given - the lines of files (each with its number before the |), the answers of "
            + "earlier steps, their findings - and say so where something is missing rather than assume it. Answer in "
            + "the language of the prompt.";

    static final String EXTRACT_SYSTEM = "You take values out of one document for a scheduled task of a company - an "
            + "invoice, a receipt, a delivery note, a form. The document's text follows, each line with its number "
            + "before the |; images and scanned pages were read into text before. Fill every field from what the "
            + "document says, wherever it stands and whatever it calls it. Never guess or compute a value the "
            + "document does not show: leave it out (null). When something cannot be settled - the document is not "
            + "what the fields describe, a field has several different values, a value is unreadable - fill what you "
            + "can and say why in \"unclear\". Your last answer is only this JSON object, nothing before or after it:\n"
            + "{\"values\": {\"<field>\": <value or null>}, \"unclear\": \"<why, in one sentence>\" or null}\n"
            + "Dates as YYYY-MM-DD; amounts, numbers and percentages as JSON numbers with . as the decimal separator "
            + "(19 for 19 %); a choice as exactly one of its words.";

    private TaskPrompts() {
    }

    /** The fields an extract step asks for, each with its type, whether a row needs it and what it is. */
    static String fields(List<Field> fields) {
        StringBuilder text = new StringBuilder("\n\n=== The fields ===");
        for (Field field : fields) {
            text.append("\n- ").append(field.name()).append(" (").append(field.type().written());
            if (!field.values().isEmpty()) {
                text.append(": ").append(String.join(", ", field.values()));
            }
            text.append(field.required() ? ", required" : "").append(")");
            if (field.description() != null) {
                text.append(" - ").append(field.description());
            }
        }
        return text.toString();
    }

    static String extractAgain(String problem) {
        return "Your answer was not the result asked for: " + problem + ". Give the JSON object again - "
                + "only it, with the fields' values as they are asked for.";
    }

    static String system(StepType type) {
        return (type == StepType.VERIFY ? VERIFY_SYSTEM : AGENT_SYSTEM) + FORM;
    }

    static String prompt(List<String> choices) {
        if (choices.isEmpty()) {
            return PROMPT_SYSTEM;
        }
        return PROMPT_SYSTEM + " After your answer, end with one line that picks exactly one of these words: "
                + String.join(", ", choices) + " - written as CHOICE: <word>";
    }

    /** For an agent answering a step with choices: the one rule it needs - none without choices. */
    static String agentChoice(List<String> choices) {
        if (choices.isEmpty()) {
            return "";
        }
        return "\n\nEnd your answer with one line that picks exactly one of these words: " + String.join(", ", choices)
                + " - written as CHOICE: <word>";
    }

    static String chooseAgain(List<String> choices) {
        return "Your answer did not end with a line CHOICE: <word> naming one of " + String.join(", ", choices)
                + ". Give your answer again and end with that line.";
    }

    /** When the step asks - in the installation's time zone, the one its files are named and written in. */
    static String now(ZonedDateTime now) {
        return "It is now " + NOW.format(now) + " (" + now.getZone() + ").";
    }

    static String checkPoints(List<CheckPoint> checks) {
        StringBuilder text = new StringBuilder();
        for (CheckPoint check : checks) {
            text.append("\n\n=== Check point \"").append(check.name()).append("\" (")
                    .append(check.severity().name().toLowerCase(Locale.ROOT)).append(") ===\n").append(check.instruction());
            if (check.severity() == Severity.REPORT) {
                text.append("\n(A report, not a question: \"finding\" when the lines give anything to report - your note "
                        + "is the report itself, as the mail shows it, with the lines as evidence; \"ok\" only when "
                        + "there is nothing. Do not judge whether it matters. Report on every line, not only what a "
                        + "search finds: read a file given as an overview with read_file_lines from its first to its "
                        + "last line - a rare line counts as much as a frequent one.)");
            }
        }
        return text.toString();
    }

    /** The findings of the step verified (or the verification's notes for the step itself, going back). */
    static String findings(String heading, List<Map<String, Object>> checks) {
        StringBuilder text = new StringBuilder("\n\n").append(heading);
        for (Map<String, Object> check : checks) {
            text.append("\n- \"").append(check.get("name")).append("\": ").append(check.get("status"))
                    .append(" - ").append(check.get("note"));
            if (check.get("evidence") instanceof List<?> evidence) {
                for (Object line : evidence) {
                    if (line instanceof Map<?, ?> found) {
                        text.append("\n  ").append(found.get("file")).append(" ").append(found.get("line")).append("| ")
                                .append(found.get("text"));
                    }
                }
            }
        }
        return text.toString();
    }

    /**
     * Above the files of one input step - the instructions name the steps, the files only their names. A step cut at
     * its limit says so: what comes after its last lines was not read, and must not look missing.
     */
    static String stepFiles(String step, boolean cut) {
        if (!cut) {
            return "\n\n=== Step \"" + step + "\" - the files it gave follow ===";
        }
        return "\n\n=== Step \"" + step + "\" - the files it gave follow; it was CUT at its limit: what comes after "
                + "their last lines was not read this run, so nothing after them can be called missing or not done "
                + "- call what depends on it unclear ===";
    }

    /** An input step that kept no lines: said, so the model does not look for it and call its check points unclear. */
    static String noLines(String step) {
        return "\n\n=== Step \"" + step + "\" gave no lines this run (nothing new since the last run, or nothing "
                + "that passed its filters) - nothing of it to check ===";
    }

    static String wholeFile(RunFile file) {
        StringBuilder text = new StringBuilder("\n\n=== File \"").append(file.name())
                .append("\" (the lines the task kept, each with its number in the file before the |) ===\n");
        for (RunFile.Line line : file.lines()) {
            text.append(numbered(line)).append('\n');
        }
        return text.toString();
    }

    static String overview(RunFile file) {
        return "\n\n=== File \"" + file.name() + "\" (too large to go whole - read it with search_file and "
                + "read_file_lines; each line starts with its number in the file before the |) ===\n"
                + file.lines().size() + " lines";
    }

    static String cut(RunFile file, int keep) {
        StringBuilder text = new StringBuilder("\n\n=== File \"").append(file.name()).append("\" (its first lines - ")
                .append("CUT: the rest could not be given and nobody judges it, so nothing in it can be called ok, ")
                .append("missing or not done - call what depends on it unclear and say so in your notes) ===\n");
        int used = 0;
        for (RunFile.Line line : file.lines()) {
            String numbered = numbered(line);
            if (used + numbered.length() > keep) {
                break;
            }
            text.append(numbered).append('\n');
            used += numbered.length() + 1;
        }
        return text.toString();
    }

    static String numbered(RunFile.Line line) {
        return line.number() + "| " + line.text();
    }

    static String again(String problem) {
        return "Your answer was not the result asked for: " + problem + ". " + FORM;
    }
}
