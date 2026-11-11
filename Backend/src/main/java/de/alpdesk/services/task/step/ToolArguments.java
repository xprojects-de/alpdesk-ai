package de.alpdesk.services.task.step;

import de.alpdesk.services.task.TaskDefinition.Field;
import de.alpdesk.services.task.TaskDefinition.FieldType;
import io.micronaut.core.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A call_tool step's arguments against the tool's input schema (docs/architecture/tasks.md "call_tool"): a row's fields
 * by their names, its source, the step's fixed "with" - checked when the task is checked, built for every call. A
 * property the schema does not name is left out of a call; a schema naming no properties takes everything. A "with"
 * the schema does not name is refused at the check: it is a typo, and the tool would never see its value.
 */
public final class ToolArguments {

    /** The argument a call with every row takes them in. */
    public static final String ROWS = "rows";
    public static final String SOURCE = "source";

    private ToolArguments() {
    }

    /** Why the tool does not take these arguments - null when they fit. */
    public static @Nullable String problem(Map<String, Object> schema, List<Field> fields, Map<String, String> with,
                                           boolean all) {
        Map<String, Object> properties = properties(schema);
        Set<String> given = new LinkedHashSet<>(with.keySet());
        if (all) {
            given.add(ROWS);
            if (properties != null && !types(properties.get(ROWS)).contains("array")) {
                return "it takes no list \"rows\" - call it once per row";
            }
        } else {
            fields.forEach(field -> given.add(field.name()));
            given.add(SOURCE);
        }
        for (String required : required(schema)) {
            if (!given.contains(required)) {
                return "it needs \"" + required + "\", which neither a field nor with gives";
            }
        }
        if (properties == null) {
            return null;
        }
        if (!all) {
            for (Field field : fields) {
                Set<String> types = types(properties.get(field.name()));
                if (properties.containsKey(field.name()) && !types.isEmpty() && !fits(field.type(), types)) {
                    return "the field \"" + field.name() + "\" is a " + field.type().written() + ", the tool wants "
                            + String.join(" or ", types);
                }
            }
        }
        String unknown = unknownWith(properties, with.keySet());
        if (unknown != null) {
            return unknown;
        }
        for (Map.Entry<String, String> entry : with.entrySet()) {
            Set<String> types = types(properties.get(entry.getKey()));
            if (!types.isEmpty() && withValue(entry.getValue(), types) == null) {
                return "with \"" + entry.getKey() + "\" is no " + String.join(" or ", types);
            }
        }
        return null;
    }

    /**
     * Why a source's tool does not take these arguments - its with, and a file tool's key (null: none) - null: they
     * fit.
     */
    public static @Nullable String problemOfSource(Map<String, Object> schema, Set<String> with, @Nullable String key) {
        Set<String> given = new LinkedHashSet<>(with);
        if (key != null) {
            given.add(key);
        }
        for (String required : required(schema)) {
            if (!given.contains(required)) {
                return "it needs \"" + required + "\", which with does not give";
            }
        }
        Map<String, Object> properties = properties(schema);
        return properties == null ? null : unknownWith(properties, with);
    }

    private static @Nullable String unknownWith(Map<String, Object> properties, Set<String> with) {
        for (String name : with) {
            if (!properties.containsKey(name)) {
                return "it takes no \"" + name + "\" - with names an argument the tool does not have";
            }
        }
        return null;
    }

    /** The arguments of one row's call: its fields with a value, its source, the step's with. */
    public static Map<String, Object> forRow(Map<String, Object> schema, List<Field> fields, Map<String, Object> values,
                                             String source, Map<String, String> with) {
        Map<String, Object> properties = properties(schema);
        Map<String, Object> arguments = rowArguments(properties, fields, values, source);
        addWith(arguments, properties, with);
        return arguments;
    }

    /** The arguments of a call with every row: the rows as a list "rows", the step's with. */
    public static Map<String, Object> forAll(Map<String, Object> schema, List<Field> fields,
                                             List<Map.Entry<String, Map<String, Object>>> rows, Map<String, String> with) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> row : rows) {
            list.add(rowArguments(null, fields, row.getValue(), row.getKey()));
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(ROWS, list);
        addWith(arguments, properties(schema), with);
        return arguments;
    }

    private static Map<String, Object> rowArguments(@Nullable Map<String, Object> properties, List<Field> fields,
                                                    Map<String, Object> values, String source) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Field field : fields) {
            Object value = values.get(field.name());
            if (value != null && (properties == null || properties.containsKey(field.name()))) {
                arguments.put(field.name(), typed(field.type(), value));
            }
        }
        if (properties == null || properties.containsKey(SOURCE)) {
            arguments.put(SOURCE, source);
        }
        return arguments;
    }

    /** A source's arguments: its with alone, each value as the schema's property wants it. */
    public static Map<String, Object> forSource(Map<String, Object> schema, Map<String, String> with) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        addWith(arguments, properties(schema), with);
        return arguments;
    }

    private static void addWith(Map<String, Object> arguments, @Nullable Map<String, Object> properties,
                                Map<String, String> with) {
        for (Map.Entry<String, String> entry : with.entrySet()) {
            // Only a tool's schema read again since the check gets here without the property: it is left out too.
            if (properties != null && !properties.containsKey(entry.getKey())) {
                continue;
            }
            Set<String> types = properties == null ? Set.of() : types(properties.get(entry.getKey()));
            Object value = types.isEmpty() ? entry.getValue() : withValue(entry.getValue(), types);
            arguments.put(entry.getKey(), value == null ? entry.getValue() : value);
        }
    }

    /** A number as a JSON number, a date as its ISO text, a text as it is. */
    private static Object typed(FieldType type, Object value) {
        return switch (type) {
            case AMOUNT, NUMBER, PERCENT -> new BigDecimal(String.valueOf(value));
            default -> String.valueOf(value);
        };
    }

    private static boolean fits(FieldType type, Set<String> types) {
        return switch (type) {
            case AMOUNT, PERCENT -> types.contains("number");
            case NUMBER -> types.contains("number") || types.contains("integer");
            case TEXT, DATE, CHOICE -> types.contains("string");
        };
    }

    /** A with value as the property's type wants it - null when it is none. */
    private static @Nullable Object withValue(String text, Set<String> types) {
        if (types.contains("string")) {
            return text;
        }
        if ((types.contains("number") || types.contains("integer")) && text.strip().matches("-?\\d+(\\.\\d+)?")) {
            BigDecimal number = new BigDecimal(text.strip());
            return types.contains("number") || number.stripTrailingZeros().scale() <= 0 ? number : null;
        }
        if (types.contains("boolean") && (text.equals("true") || text.equals("false"))) {
            return Boolean.valueOf(text);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> properties(Map<String, Object> schema) {
        return schema.get("properties") instanceof Map<?, ?> properties ? (Map<String, Object>) properties : null;
    }

    private static List<String> required(Map<String, Object> schema) {
        List<String> required = new ArrayList<>();
        if (schema.get("required") instanceof List<?> names) {
            names.forEach(name -> required.add(String.valueOf(name)));
        }
        return required;
    }

    /** The JSON types a property allows: "type" as a text or a list; none named: any. */
    private static Set<String> types(@Nullable Object property) {
        Set<String> types = new LinkedHashSet<>();
        if (property instanceof Map<?, ?> map) {
            if (map.get("type") instanceof String type) {
                types.add(type);
            } else if (map.get("type") instanceof List<?> list) {
                list.forEach(type -> types.add(String.valueOf(type)));
            }
        }
        return types;
    }
}
