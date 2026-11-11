package de.alpdesk.services.task.step;

import de.alpdesk.entities.task.ReadPosition;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.filestore.FileStoreService;
import de.alpdesk.services.filestore.FileStoreSession;
import de.alpdesk.services.filestore.NamePattern;
import de.alpdesk.services.filestore.RemoteFile;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskDefinition;
import de.alpdesk.services.task.TaskDefinition.ReadFiles;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskResults;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * read_files (docs/architecture/tasks.md "Steps"): the files of a store's folder whose names match the pattern for
 * the last days, each read from where the task stopped last time, filtered to the lines the task wants - stored as the
 * run's files in the tenant database. The state gets their ids and the new read positions; the positions are written
 * only after the run succeeded, so a failed run reads the same lines again.
 */
@Singleton
public class ReadFilesStep {

    static final int MAX_FILES = 50;
    static final int MAX_BYTES = 20 * 1024 * 1024;
    public static final int MAX_LINES = 20_000;
    static final int MAX_LINE_LENGTH = 2000;
    /** How much of a file's end a first run starting at the end reads to find its last line break. */
    static final int TAIL_BYTES = 64 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    private final FileStoreService stores;
    private final TaskRunRepository runs;
    private final ZoneId zone;
    private final Clock clock;

    public ReadFilesStep(FileStoreService stores, TaskRunRepository runs, @Value("${app.timezone}") String zone) {
        this(stores, runs, ZoneId.of(zone), Clock.systemUTC());
    }

    ReadFilesStep(FileStoreService stores, TaskRunRepository runs, ZoneId zone, Clock clock) {
        this.stores = stores;
        this.runs = runs;
        this.zone = zone;
        this.clock = clock;
    }

    public StepOutcome run(StepContext context) {
        ReadFiles settings = context.step().readFiles();
        Map<String, ReadPosition> known = new HashMap<>();
        if (settings.onlyNew()) {
            for (ReadPosition position : runs.findPositions(context.dataSource(), context.taskId(), settings.storeId())) {
                known.put(position.path(), position);
            }
        }
        // Only the task's very first run on this store - a file new later (tomorrow's log) is read from its start
        boolean startAtEnd = settings.onlyNew() && settings.startsAtEnd() && known.isEmpty();
        LineMasks masks = new LineMasks(settings.masks());
        Reading reading = new Reading();
        String folder = stores.folderOf(context.tenant().id(), settings.storeId(), settings.folder());
        try (FileStoreSession session = stores.open(context.tenant().id(), settings.storeId())) {
            for (RemoteFile file : chosen(session.list(folder), settings, reading)) {
                String path = folder.endsWith("/") ? folder + file.name() : folder + "/" + file.name();
                if (startAtEnd) {
                    reading.positions.add(position(settings.storeId(), path, endOfLastLine(session, file, path), 0,
                            file.modified()));
                } else {
                    read(context, session, settings, masks, file, path, known.get(path), reading);
                }
            }
        }
        if (startAtEnd) {
            // The first run is over even when no file matched yet: without a position on the store the next run would
            // start at the end again and skip the lines written in between. The folder itself never names a file.
            reading.positions.add(position(settings.storeId(), folder, 0, 0, null));
        }
        // A file that must keep growing (a cron's heartbeat) and did not: a run with no lines anywhere asks no model, so
        // only a failed run - mailed to the notify list - says so. Not on a first run that starts at the end on purpose.
        if (settings.minLines() != null && !startAtEnd && reading.lines < settings.minLines()) {
            throw new ServiceException(HttpStatus.CONFLICT, "task.tooFewLines", "The step " + context.step().id()
                    + " read " + reading.lines + " new lines, at least " + settings.minLines() + " expected - its files"
                    + " did not grow as they should since the last run", Map.of("step", context.step().id(),
                    "lines", String.valueOf(reading.lines), "min", String.valueOf(settings.minLines())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.FILES, reading.fileIds.size());
        result.put(TaskResults.LINES, reading.lines);
        result.put(TaskResults.TRUNCATED, reading.truncated);
        result.put(TaskResults.FILE_IDS, reading.fileIds);
        result.put(TaskResults.POSITIONS, settings.onlyNew() ? reading.positions : List.of());
        return StepOutcome.of(result);
    }

    /** The names matching the pattern for one of the days; above the limit the newest - read in the order of their names. */
    private List<RemoteFile> chosen(List<RemoteFile> files, ReadFiles settings, Reading reading) {
        Set<String> patterns = patterns(settings.pattern(), settings.days());
        List<RemoteFile> matching = new ArrayList<>();
        for (RemoteFile file : files) {
            if (!file.folder() && patterns.stream().anyMatch(pattern -> NamePattern.matches(pattern, file.name()))) {
                matching.add(file);
            }
        }
        if (matching.size() > MAX_FILES) {
            reading.truncated = true;
            matching.sort(Comparator.comparing(RemoteFile::modified, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .reversed());
            matching = new ArrayList<>(matching.subList(0, MAX_FILES));
        }
        matching.sort(Comparator.comparing(RemoteFile::name));
        return matching;
    }

    /**
     * The pattern for today and the days before, in the installation's time zone: each date placeholder written as
     * that day (a date has no * or ?, the compiler allows only yMd and separators).
     */
    Set<String> patterns(String pattern, int days) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Set<String> patterns = new LinkedHashSet<>();
        for (int day = 0; day < days; day++) {
            LocalDate date = today.minusDays(day);
            patterns.add(PLACEHOLDER.matcher(pattern).replaceAll(placeholder -> Matcher.quoteReplacement(
                    date.format(DateTimeFormatter.ofPattern(placeholder.group(1))))));
        }
        return patterns;
    }

    /**
     * Reads one file from its position on - a rotated file is read from its start: one smaller than its position, or
     * one without a line break right before it (a position read up to always stops after a complete line, so a new
     * file that grew past the old offset has other bytes there - read along with the data, not as a request of its
     * own: FTPS drops a connection whose transfer was broken off). Not for a position a first run starting at the end
     * set (line 0): that one may stand inside a line. Only complete lines count: a last line without its line break
     * may still be written, so the position stops before it and the next run reads it whole.
     */
    private void read(StepContext context, FileStoreSession session, ReadFiles settings, LineMasks masks,
                      RemoteFile file, String path, @Nullable ReadPosition position, Reading reading) {
        boolean rotated = position == null || file.size() < position.byteOffset();
        long offset = rotated ? 0 : position.byteOffset();
        long lineNumber = rotated ? 0 : position.lineNumber();
        long left = MAX_BYTES - reading.bytes;
        if (file.size() <= offset || left <= 0 || reading.lines >= MAX_LINES) {
            reading.truncated |= file.size() > offset;
            if (rotated && position != null) {
                // a rotated file still empty (or not read for the limits): the next run starts at its beginning
                reading.positions.add(position(settings.storeId(), path, 0, 0, file.modified()));
            }
            return;
        }
        int length = (int) Math.min(file.size() - offset, left);
        reading.truncated |= length < file.size() - offset;
        int skip = offset > 0 && lineNumber > 0 ? 1 : 0;
        byte[] bytes = session.read(path, offset - skip, length + skip);
        if (skip == 1 && (bytes.length == 0 || bytes[0] != '\n')) {
            read(context, session, settings, masks, file, path, null, reading);
            return;
        }
        reading.bytes += bytes.length - skip;
        int complete = lastLineBreak(bytes) + 1;
        List<RunFile.Line> kept = new ArrayList<>();
        int start = skip;
        for (int i = skip; i < complete; i++) {
            if (bytes[i] != '\n') {
                continue;
            }
            if (reading.lines + kept.size() >= MAX_LINES) {
                reading.truncated = true;
                complete = start;
                break;
            }
            lineNumber++;
            String line = decode(bytes, start, i - start);
            start = i + 1;
            if (wanted(line, settings)) {
                // masked before it is cut: a value at the cut must not stay there in half
                kept.add(new RunFile.Line(lineNumber, cut(masks.apply(line))));
            }
        }
        reading.lines += kept.size();
        if (!kept.isEmpty()) {
            reading.fileIds.add(runs.saveFile(context.dataSource(), context.runId(), context.step().id(),
                    runFileName(context.task(), settings, file), kept));
        }
        reading.positions.add(position(settings.storeId(), path, offset + complete - skip, lineNumber, file.modified()));
    }

    /**
     * The name a run file goes by - the model's evidence and the mail name it. The file's own while every read_files
     * step of the task reads one folder of one store; else with its store and folder ("Webserver:logs/app.log"), so two
     * files of one name from two places stay two. Two steps reading the same file still give one name, and the tools
     * see their lines together.
     */
    static String runFileName(TaskDefinition task, ReadFiles settings, RemoteFile file) {
        if (readsOneFolder(task)) {
            return file.name();
        }
        String folder = FileStoreService.folderBelow("/", settings.folder());
        return settings.store() + ":" + ("/".equals(folder) ? "" : folder.substring(1) + "/") + file.name();
    }

    private static boolean readsOneFolder(TaskDefinition task) {
        Set<String> places = new HashSet<>();
        for (Step step : task.steps()) {
            if (step.readFiles() != null) {
                places.add(step.readFiles().storeId() + ":" + FileStoreService.folderBelow("/", step.readFiles().folder()));
            }
        }
        return places.size() <= 1;
    }

    /**
     * Where a first run starting at the end puts the position: after the last complete line - a line still being
     * written is read whole by the next run. Its line numbers then count from here; the lines before were never read.
     */
    private static long endOfLastLine(FileStoreSession session, RemoteFile file, String path) {
        int tail = (int) Math.min(file.size(), TAIL_BYTES);
        if (tail == 0) {
            return 0;
        }
        long from = file.size() - tail;
        int lineBreak = lastLineBreak(session.read(path, from, tail));
        return lineBreak < 0 ? file.size() : from + lineBreak + 1;
    }

    private static int lastLineBreak(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** UTF-8, else Latin-1 - log files of older software are often written in it. Without the line's \r. */
    private static String decode(byte[] bytes, int from, int length) {
        int end = length > 0 && bytes[from + length - 1] == '\r' ? length - 1 : length;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, from, end)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, from, end, StandardCharsets.ISO_8859_1);
        }
    }

    /** Plain texts, as written: at least one of include (when given) and none of exclude. */
    private static boolean wanted(String line, ReadFiles settings) {
        if (settings.exclude().stream().anyMatch(line::contains)) {
            return false;
        }
        return settings.include().isEmpty() || settings.include().stream().anyMatch(line::contains);
    }

    private static String cut(String line) {
        return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH) + " [...]";
    }

    /** As a map: the graph's state is plain JSON, read back as maps after a restart. */
    private static Map<String, Object> position(Long storeId, String path, long offset, long line,
                                                @Nullable Instant modified) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("storeId", storeId);
        position.put("path", path);
        position.put("byteOffset", offset);
        position.put("lineNumber", line);
        position.put("modifiedAt", modified == null ? null : modified.toEpochMilli());
        return position;
    }

    /** The positions a run's read_files steps reached - written once the run succeeded. */
    public static List<ReadPosition> positions(Map<String, Object> result) {
        List<ReadPosition> positions = new ArrayList<>();
        if (!(result.get(TaskResults.POSITIONS) instanceof List<?> list)) {
            return positions;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> position) {
                Object modified = position.get("modifiedAt");
                positions.add(new ReadPosition(((Number) position.get("storeId")).longValue(),
                        (String) position.get("path"), ((Number) position.get("byteOffset")).longValue(),
                        ((Number) position.get("lineNumber")).longValue(),
                        modified instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : null));
            }
        }
        return positions;
    }

    /** What the step has read so far, over all its files. */
    private static final class Reading {
        final List<Long> fileIds = new ArrayList<>();
        final List<Map<String, Object>> positions = new ArrayList<>();
        long bytes;
        int lines;
        boolean truncated;
    }
}
