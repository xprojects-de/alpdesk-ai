package de.alpdesk.services.chat;

import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tools.ExploredFile;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The text files sent with a question answered once - e.g. a machine's logs to check against the company's documents
 * (docs/architecture/ai-features.md "Files sent with a question"). Small ones go with the question whole. A larger one
 * is not cut or condensed: the question carries an overview of it, and the model reads what it needs with search_file
 * and read_file_lines - it decides what matters, not a filter of ours (decided 7 October 2026).
 */
@Singleton
public class SentFiles {

    public static final int MAX_FILES = 20;
    private static final int MAX_NAME = 200;

    private final int maxText;
    private final int inlineText;

    public SentFiles(@Value("${app.chat.sent-files.max-text}") int maxText,
                     @Value("${app.chat.sent-files.inline-text}") int inlineText) {
        this.maxText = maxText;
        this.inlineText = inlineText;
    }

    /** What goes with the question: its text after the question, and the files the model reads with tools. */
    public record Prepared(String text, List<ExploredFile> explored) {
    }

    /**
     * 400 for more than {@link #MAX_FILES} files, a missing name or content, a name twice (the model reads a file by its
     * name), or more than max-text characters together.
     */
    public void check(List<SentFile> files) {
        if (files.size() > MAX_FILES) {
            throw ServiceException.badRequest("sentFile.tooMany", "At most " + MAX_FILES
                    + " files may come with a question",
                    Map.of("max", String.valueOf(MAX_FILES)));
        }
        long text = 0;
        Set<String> names = new HashSet<>();
        for (SentFile file : files) {
            if (file.name() == null || file.name().isBlank() || file.name().length() > MAX_NAME) {
                throw ServiceException.badRequest("sentFile.nameLength", "Every file needs a name of 1 to " + MAX_NAME
                        + " characters",
                        Map.of("max", String.valueOf(MAX_NAME)));
            }
            if (!names.add(file.name().strip().toLowerCase(Locale.ROOT))) {
                throw ServiceException.badRequest("sentFile.duplicateName", "Two files are named \"" + file.name()
                        + "\" - every file needs a name of its own", Map.of("name", file.name()));
            }
            if (file.content() == null) {
                throw ServiceException.badRequest("sentFile.empty", "The file \"" + file.name() + "\" has no content",
                        Map.of("name", file.name()));
            }
            text += file.content().length();
        }
        if (text > maxText) {
            throw ServiceException.badRequest("sentFile.tooMuchText", "The files may have at most " + maxText
                    + " characters of text together - send the parts that matter",
                    Map.of("max", String.valueOf(maxText)));
        }
    }

    /**
     * Files that fit into inline-text together go with the question whole; otherwise each one in turn while the budget
     * lasts, and the rest as an overview for the tools. A model that cannot call tools gets the rest cut at the budget,
     * with a note saying so.
     */
    public Prepared prepare(List<SentFile> files, boolean callsTools) {
        StringBuilder text = new StringBuilder();
        List<ExploredFile> explored = new ArrayList<>();
        long left = inlineText;
        for (SentFile file : files) {
            if (file.content().length() <= left) {
                text.append(whole(file));
                left -= file.content().length();
            } else if (callsTools) {
                text.append(overview(file));
                explored.add(ExploredFile.of(file.name().strip(), file.content()));
            } else {
                text.append(cut(file, (int) left));
                left = 0;
            }
        }
        return new Prepared(text.toString(), explored);
    }

    private static String whole(SentFile file) {
        return "\n\n=== The file \"" + file.name() + "\" (sent with the question) ===\n" + file.content();
    }

    private static String cut(SentFile file, int keep) {
        return "\n\n=== The file \"" + file.name() + "\" (sent with the question, too large: its first " + keep + " of "
                + file.content().length() + " characters - say that the rest could not be read) ===\n"
                + file.content().substring(0, keep);
    }

    /** Its size only: what is in it, the model finds with the tools - whatever kind of text it is. */
    static String overview(SentFile file) {
        return "\n\n=== The file \"" + file.name().strip() + "\" (sent with the question, too large to go with it whole - "
                + "read it with search_file and read_file_lines) ===\n"
                + file.content().length() + " characters, " + file.content().lines().count() + " lines";
    }
}
