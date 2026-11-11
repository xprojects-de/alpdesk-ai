package de.alpdesk.services.tools;

import java.util.List;

/**
 * A file sent with a question that is too large to go with it whole (services.chat.SentFiles): the model reads it
 * through search_file and read_file_lines. Kept for that one answer only - never stored.
 */
public record ExploredFile(String name, List<String> lines) {

    public static ExploredFile of(String name, String content) {
        return new ExploredFile(name, content.lines().toList());
    }
}
