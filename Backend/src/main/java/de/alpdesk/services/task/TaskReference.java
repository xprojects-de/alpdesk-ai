package de.alpdesk.services.task;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The reference of the task format (resources/tasks/reference/<language>.md): what the editor's info button shows, and
 * what a draft's model is told (English) - one text, so the two never differ.
 */
@Singleton
public class TaskReference {

    static final String ENGLISH = "en";
    private final Map<String, String> texts = Map.of(ENGLISH, load(ENGLISH), "de", load("de"));

    /** In the language asked for, else English. */
    public String markdown(String language) {
        return texts.getOrDefault(language, texts.get(ENGLISH));
    }

    private static String load(String language) {
        String path = "/tasks/reference/" + language + ".md";
        try (InputStream in = TaskReference.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("The task reference " + path + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
