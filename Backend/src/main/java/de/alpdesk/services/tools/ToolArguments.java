package de.alpdesk.services.tools;

import de.alpdesk.services.common.ServiceException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.micronaut.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** The arguments of a tool call (JSON from the model); a missing or wrong one is a 400 the Toolbox hands back to it. */
final class ToolArguments {

    private final Map<?, ?> values;

    private ToolArguments(Map<?, ?> values) {
        this.values = values;
    }

    static ToolArguments of(JsonMapper json, ToolExecutionRequest request) {
        try {
            Map<?, ?> values = json.readValue(request.arguments() == null || request.arguments().isBlank()
                    ? "{}" : request.arguments(), Map.class);
            return new ToolArguments(values == null ? Map.of() : values);
        } catch (IOException | RuntimeException e) {
            throw ServiceException.badRequest("tool.argumentsInvalid", "the arguments are not valid JSON");
        }
    }

    String requiredString(String name) {
        if (values.get(name) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw ServiceException.badRequest("tool.argumentMissing", "the argument " + name + " is missing",
                Map.of("name", name));
    }

    String optionalString(String name) {
        return values.get(name) instanceof String value && !value.isBlank() ? value : null;
    }

    long requiredLong(String name) {
        Object value = values.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                // reported below
            }
        }
        throw ServiceException.badRequest("tool.argumentNotNumber", "the argument " + name + " must be a number",
                Map.of("name", name));
    }

    /** A list of texts, at least one. */
    List<String> requiredStrings(String name) {
        if (!(values.get(name) instanceof List<?> list) || list.isEmpty()) {
            throw ServiceException.badRequest("tool.argumentNotTextList", "the argument " + name
                    + " must be a list of texts", Map.of("name", name));
        }
        List<String> texts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw ServiceException.badRequest("tool.argumentNotTextList", "the argument " + name
                        + " must be a list of texts", Map.of("name", name));
            }
            texts.add(text);
        }
        return texts;
    }

    /** A list of texts, at least one, each once in the order given - a path named twice is read once. */
    List<String> requiredDistinctStrings(String name) {
        return List.copyOf(new LinkedHashSet<>(requiredStrings(name)));
    }

    /** A list of numbers - empty when left out. */
    List<Long> optionalLongs(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw ServiceException.badRequest("tool.argumentNotNumberList", "the argument " + name
                    + " must be a list of numbers",
                    Map.of("name", name));
        }
        List<Long> numbers = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Number number)) {
                throw ServiceException.badRequest("tool.argumentNotNumberList", "the argument " + name
                        + " must be a list of numbers",
                        Map.of("name", name));
            }
            numbers.add(number.longValue());
        }
        return numbers;
    }

    Integer optionalInt(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw ServiceException.badRequest("tool.argumentNotNumber", "the argument " + name + " must be a number",
                    Map.of("name", name));
        }
    }
}
