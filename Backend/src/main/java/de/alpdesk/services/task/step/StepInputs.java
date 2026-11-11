package de.alpdesk.services.task.step;

import io.micronaut.core.annotation.Nullable;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.TaskResults;
import de.alpdesk.services.tools.ExploredFile;
import de.alpdesk.services.tools.ToolContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** What agent, verify and prompt steps share: the run's files their inputs gave, and how they go to the model. */
public final class StepInputs {

    static final int INLINE_CHARACTERS = 60_000;

    private StepInputs() {
    }

    /** One input step and the run's files it gave - none when it kept no lines this run; cut when it hit its limit. */
    record Input(String step, List<RunFile> files, boolean cut) {
    }

    /** A run file's lines as one text - a prompt's answer as it was written. */
    public static String text(RunFile file) {
        return String.join("\n", file.lines().stream().map(RunFile.Line::text).toList());
    }

    /**
     * The files under their steps, whole while they fit; then, for a model that calls tools, as an overview to read
     * with the tools - else cut, with a note. A step without lines is named too, so the model knows it is empty.
     */
    /** tools null: nothing to explore with (the files go to an A2A agent) - they are cut. */
    static String filesText(List<Input> inputs, @Nullable ToolContext tools, boolean callsTools) {
        StringBuilder text = new StringBuilder();
        Map<String, Explored> explored = new LinkedHashMap<>();
        int left = INLINE_CHARACTERS;
        for (Input input : inputs) {
            if (input.files().isEmpty()) {
                text.append(TaskPrompts.noLines(input.step()));
                continue;
            }
            text.append(TaskPrompts.stepFiles(input.step(), input.cut()));
            for (RunFile file : input.files()) {
                String whole = TaskPrompts.wholeFile(file);
                if (whole.length() <= left) {
                    text.append(whole);
                    left -= whole.length();
                } else if (callsTools) {
                    text.append(TaskPrompts.overview(file));
                    explored.computeIfAbsent(file.name().toLowerCase(Locale.ROOT), _ -> new Explored(file.name()))
                            .add(file);
                } else {
                    text.append(TaskPrompts.cut(file, left));
                    left = 0;
                }
            }
        }
        if (tools != null) {
            tools.exploredFiles(explored.values().stream().map(Explored::file).toList());
        }
        return text.toString();
    }

    /** The inputs of these steps, in their order. */
    static List<Input> inputs(TaskRunRepository runs, StepContext context, List<String> steps) {
        List<Input> inputs = new ArrayList<>();
        for (String step : steps) {
            Map<String, Object> result = context.results().get(step);
            boolean cut = result != null && Boolean.TRUE.equals(result.get(TaskResults.TRUNCATED));
            inputs.add(new Input(step, files(runs, context, List.of(step)), cut));
        }
        return inputs;
    }

    /** All the inputs' files together. */
    static List<RunFile> files(List<Input> inputs) {
        List<RunFile> files = new ArrayList<>();
        for (Input input : inputs) {
            files.addAll(input.files());
        }
        return files;
    }

    /** The run's files these steps gave: the lines read_files kept, the answers prompt steps stored. */
    static List<RunFile> files(TaskRunRepository runs, StepContext context, List<String> steps) {
        List<Long> ids = new ArrayList<>();
        for (String input : steps) {
            Map<String, Object> result = context.results().get(input);
            if (result != null && result.get(TaskResults.FILE_IDS) instanceof List<?> fileIds) {
                for (Object id : fileIds) {
                    ids.add(((Number) id).longValue());
                }
            }
        }
        return ids.isEmpty() ? List.of() : runs.findFiles(context.dataSource(), context.runId(), ids);
    }

    /**
     * One file for search_file and read_file_lines: two steps reading the same file with other filters give it twice,
     * and the tools find a file by its name - so their lines go together, in the file's order.
     */
    private static final class Explored {
        private final String name;
        private final TreeMap<Long, String> lines = new TreeMap<>();

        Explored(String name) {
            this.name = name;
        }

        void add(RunFile file) {
            for (RunFile.Line line : file.lines()) {
                lines.put(line.number(), TaskPrompts.numbered(line));
            }
        }

        ExploredFile file() {
            return new ExploredFile(name, List.copyOf(lines.values()));
        }
    }
}
