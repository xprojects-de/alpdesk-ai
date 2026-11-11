package de.alpdesk.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An HTML building block of an e-mail, {@code resources/mail/<name>.html}, with {@code {{placeholders}}}.
 * {@link #fill} puts in the values: text is always HTML-escaped, only {@link Html} (another filled block) goes in
 * as it is. Every placeholder needs a value, and values are never scanned for placeholders themselves.
 */
public final class MailTemplate {

    /** HTML that is inserted as it is: a filled template, or several joined. */
    public record Html(String value) {

        public static Html join(List<Html> parts) {
            return new Html(parts.stream().map(Html::value).collect(Collectors.joining()));
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final String name;
    private final String html;

    private MailTemplate(String name, String html) {
        this.name = name;
        this.html = html;
    }

    public static MailTemplate load(String name) {
        String path = "/mail/" + name + ".html";
        try (InputStream in = MailTemplate.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Mail template " + path + " is missing");
            }
            return new MailTemplate(name, new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Html fill(Map<String, ?> values) {
        Matcher placeholder = PLACEHOLDER.matcher(html);
        StringBuilder result = new StringBuilder();
        while (placeholder.find()) {
            String key = placeholder.group(1);
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("Mail template " + name + " needs a value for {{" + key + "}}");
            }
            placeholder.appendReplacement(result, Matcher.quoteReplacement(render(values.get(key))));
        }
        placeholder.appendTail(result);
        return new Html(result.toString());
    }

    private static String render(Object value) {
        if (value instanceof Html html) {
            return html.value();
        }
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
