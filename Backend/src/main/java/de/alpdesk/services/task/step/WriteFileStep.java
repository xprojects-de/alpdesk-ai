package de.alpdesk.services.task.step;

import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.filestore.FileStoreService;
import de.alpdesk.services.filestore.FileStoreSession;
import de.alpdesk.services.filestore.RemoteFile;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskCompiler;
import de.alpdesk.services.task.TaskDefinition.Field;
import de.alpdesk.services.task.TaskDefinition.WriteFile;
import de.alpdesk.services.task.TaskResults;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * write_file (docs/architecture/tasks.md "write_file"): the rows of its extract step as a CSV in a store - appended to
 * the file there, or the file written anew with this run's rows. A row whose source (the document's path and checksum)
 * is in the file already is skipped: a run that wrote and then failed reads the same documents again, and nothing
 * comes twice. The file's columns are the fields, then unclear and source; fields added at the end of the task's list
 * are taken (the old rows get empty values), any other change of the columns is refused. The file's name may hold the
 * run's date and the rows' own date or choice - the rows of one run then go into several files, at most
 * {@value #MAX_FILES}. Not in a transaction: a server may take seconds to answer.
 */
@Singleton
public class WriteFileStep {

    static final int MAX_FILES = 20;
    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final List<String> ROW_COLUMNS = List.of("unclear", "source");

    private final FileStoreService stores;
    private final TaskRunRepository runs;
    private final JsonMapper json;
    private final ZoneId zone;

    public WriteFileStep(FileStoreService stores, TaskRunRepository runs, JsonMapper json,
                         @Value("${app.timezone}") String zone) {
        this.stores = stores;
        this.runs = runs;
        this.json = json;
        this.zone = ZoneId.of(zone);
    }

    public StepOutcome run(StepContext context) {
        WriteFile settings = context.step().writeFile();
        String input = context.step().input().getFirst();
        List<Field> fields = StepRows.fields(context, input);
        Map<String, List<StepRows.Row>> byFile = byFile(settings.file(), StepRows.of(runs, json, context, input),
                LocalDate.now(zone));
        if (byFile.size() > MAX_FILES) {
            throw ServiceException.conflict("task.tooManyFiles", "The rows of this run would go into " + byFile.size()
                    + " files - at most " + MAX_FILES, Map.of("count", String.valueOf(byFile.size()),
                    "max", String.valueOf(MAX_FILES)));
        }
        Written written = new Written();
        if (!byFile.isEmpty()) {
            stores.requireWritable(context.tenant().id(), settings.storeId());
            try (FileStoreSession session = stores.open(context.tenant().id(), settings.storeId())) {
                for (Map.Entry<String, List<StepRows.Row>> file : byFile.entrySet()) {
                    write(context, session, settings, fields, file.getKey(), file.getValue(), written);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.ROWS, written.rows);
        result.put(TaskResults.SKIPPED, written.skipped);
        result.put(TaskResults.WRITTEN, written.files);
        return StepOutcome.of(result);
    }

    private static final class Written {
        int rows;
        int skipped;
        final List<String> files = new ArrayList<>();
    }

    /** The rows by the file they go into, the file's placeholders filled in for each - in the files' order. */
    static Map<String, List<StepRows.Row>> byFile(String file, List<StepRows.Row> rows, LocalDate today) {
        Map<String, List<StepRows.Row>> byFile = new TreeMap<>();
        for (StepRows.Row row : rows) {
            byFile.computeIfAbsent(fileOf(file, row.values(), today), name -> new ArrayList<>()).add(row);
        }
        return byFile;
    }

    /**
     * The file's name for a row: {yyyy}, {MM}, {dd} the run's date; {<date field>:yyyy} the row's date - the run's when
     * the row has none; {<choice field>} the row's word - "none" when it has none.
     */
    static String fileOf(String file, Map<String, Object> values, LocalDate today) {
        Matcher placeholder = TaskCompiler.FILE_PLACEHOLDER.matcher(file);
        return placeholder.replaceAll(match -> Matcher.quoteReplacement(placeholderValue(match.group(1),
                match.group(2), match.group(3), values, today)));
    }

    private static String placeholderValue(@Nullable String runDatePart, @Nullable String field, @Nullable String part,
                                           Map<String, Object> values, LocalDate today) {
        if (runDatePart != null) {
            return today.format(DateTimeFormatter.ofPattern(runDatePart));
        }
        Object value = values.get(field);
        if (part == null) {
            return value == null ? "none" : String.valueOf(value);
        }
        LocalDate date = today;
        if (value != null) {
            try {
                date = LocalDate.parse(String.valueOf(value));
            } catch (DateTimeParseException e) {
                // a value that is no date was refused when the row was made - the run's date stands in
            }
        }
        return date.format(DateTimeFormatter.ofPattern(part));
    }

    private void write(StepContext context, FileStoreSession session, WriteFile settings, List<Field> fields,
                       String file, List<StepRows.Row> rows, Written written) {
        int slash = file.lastIndexOf('/');
        String folder = stores.folderOf(context.tenant().id(), settings.storeId(), slash < 0 ? "" : file.substring(0, slash));
        String name = file.substring(slash + 1);
        String path = folder.endsWith("/") ? folder + name : folder + "/" + name;
        char separator = settings.separator().charAt(0);
        List<String> header = header(fields);
        RemoteFile existing = settings.replace() ? null : session.list(folder).stream()
                .filter(entry -> !entry.folder() && entry.name().equals(name)).findFirst().orElse(null);
        List<List<String>> lines = existing == null ? new ArrayList<>()
                : kept(session, path, file, existing, header, separator);
        Set<String> sources = new HashSet<>();
        for (List<String> line : lines) {
            sources.add(line.getLast());
        }
        int added = 0;
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(settings.dateFormat());
        char decimal = settings.decimal().charAt(0);
        for (StepRows.Row row : rows) {
            // compared as written: a source the formula guard changed is in the file with its '
            if (!sources.add(CsvRows.guarded(row.row().source()))) {
                written.skipped++;
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (Field field : fields) {
                cells.add(CsvRows.cell(field, row.values().get(field.name()), decimal, dates));
            }
            cells.add(row.row().unclear() == null ? "" : CsvRows.guarded(row.row().unclear()));
            cells.add(CsvRows.guarded(row.row().source()));
            lines.add(cells);
            added++;
        }
        if (added == 0) {
            return;
        }
        session.write(path, CsvRows.format(header, lines, separator).getBytes(StandardCharsets.UTF_8));
        written.rows += added;
        written.files.add(file);
    }

    /** The fields' names, then unclear and source - the columns of every file the step writes. */
    static List<String> header(List<Field> fields) {
        List<String> header = new ArrayList<>();
        fields.forEach(field -> header.add(field.name()));
        header.addAll(ROW_COLUMNS);
        return header;
    }

    /**
     * The file's rows as they are, fitted to the header: fields added at the end of the task's list get empty values in
     * the old rows. Columns renamed, removed or moved are refused - the rows would land under the wrong names.
     */
    private static List<List<String>> kept(FileStoreSession session, String path, String file, RemoteFile existing,
                                           List<String> header, char separator) {
        if (existing.size() > MAX_BYTES) {
            throw tooLarge(file);
        }
        byte[] content = session.readWhole(path, existing.size(), MAX_BYTES);
        if (content.length > MAX_BYTES) {
            throw tooLarge(file);
        }
        CsvRows.Table table = CsvRows.parse(utf8(content, file), separator);
        if (table.header().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> oldFields = table.header().subList(0, Math.max(0, table.header().size() - ROW_COLUMNS.size()));
        List<String> newFields = header.subList(0, header.size() - ROW_COLUMNS.size());
        boolean fits = table.header().size() >= ROW_COLUMNS.size()
                && table.header().subList(oldFields.size(), table.header().size()).equals(ROW_COLUMNS)
                && newFields.size() >= oldFields.size() && newFields.subList(0, oldFields.size()).equals(oldFields);
        if (!fits) {
            throw ServiceException.conflict("task.fileHeaderChanged", "The file " + file + " has other columns ("
                    + String.join(", ", table.header()) + ") than the step writes - name a new file for the changed "
                    + "fields", Map.of("file", file, "columns", String.join(", ", table.header())));
        }
        List<List<String>> lines = new ArrayList<>();
        for (List<String> line : table.rows()) {
            List<String> fitted = new ArrayList<>();
            for (int i = 0; i < newFields.size(); i++) {
                fitted.add(i < oldFields.size() && i < line.size() ? line.get(i) : "");
            }
            fitted.add(line.size() > oldFields.size() ? line.get(oldFields.size()) : "");
            fitted.add(line.size() > oldFields.size() + 1 ? line.get(oldFields.size() + 1) : "");
            lines.add(fitted);
        }
        return lines;
    }

    /**
     * The file's text, strictly UTF-8: a CSV saved again by a spreadsheet as Windows-1252 would otherwise come back
     * with its umlauts replaced - and be written so, for good.
     */
    private static String utf8(byte[] content, String file) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw ServiceException.conflict("task.fileNotUtf8", "The file " + file + " is not UTF-8 - saved again "
                    + "by another program? Save it as CSV UTF-8, or name a new file", Map.of("file", file));
        }
    }

    private static ServiceException tooLarge(String file) {
        return ServiceException.conflict("task.fileTooLarge", "The file " + file + " is larger than "
                + MAX_BYTES / 1024 / 1024 + " MB - name a new file, e.g. one per year with {yyyy}",
                Map.of("file", file, "max", String.valueOf(MAX_BYTES / 1024 / 1024)));
    }
}
