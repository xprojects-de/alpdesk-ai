package de.alpdesk.services.task;

import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.mail.MailSender;
import de.alpdesk.mail.MailTemplate;
import de.alpdesk.mail.MailTemplate.Html;
import de.alpdesk.services.branding.Branding;
import de.alpdesk.services.branding.BrandingService;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The e-mails of a task (docs/architecture/tasks.md "Mails"), in English like everything the backend writes: the
 * findings of a send_mail step, and the run's own mails to the task's notify list - a run that failed, a run that
 * ended unclear. A mail that cannot be sent is logged and reported back - it never fails the run.
 */
@Singleton
public class TaskMails {

    private static final Logger LOG = LoggerFactory.getLogger(TaskMails.class);
    private static final MailTemplate PAGE = MailTemplate.load("page");
    private static final MailTemplate SECTION = MailTemplate.load("report-section");
    private static final MailTemplate LINE = MailTemplate.load("report-line");
    private static final MailTemplate DETAIL = MailTemplate.load("detail");
    private static final MailTemplate FOOTER = MailTemplate.load("footer-task");
    private static final MailTemplate ANSWER = MailTemplate.load("answer");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String ERROR = "#b91c1c";
    private static final String WARNING = "#b45309";
    private static final String NEUTRAL = "#374151";
    static final int MAX_EVIDENCE = 20;
    static final int MAX_EVIDENCE_LINE = 300;

    private final MailSender sender;
    private final BrandingService branding;
    private final ZoneId zone;

    public TaskMails(MailSender sender, BrandingService branding, @Value("${app.timezone}") String zone) {
        this.sender = sender;
        this.branding = branding;
        this.zone = ZoneId.of(zone);
    }

    /**
     * One mail with the answers of the prompt steps given and the findings and unclear points of the agent and verify
     * steps given; false when it could not be sent.
     */
    public boolean findings(Tenant tenant, TaskDefinition task, Instant started, List<String> recipients,
                            String subject, List<TaskAnswer> answers, List<Map<String, Object>> checks) {
        List<Html> parts = new ArrayList<>();
        String brand = branding.forTenant(tenant.id()).primaryColor();
        for (TaskAnswer answer : answers) {
            String title = answer.choice() == null ? answer.step() : answer.step() + " (" + answer.choice() + ")";
            parts.add(SECTION.fill(Map.of("title", title, "brand", brand,
                    "content", ANSWER.fill(Map.of("text", answer.text())))));
        }
        int attention = 0;
        for (Map<String, Object> check : checks) {
            String status = String.valueOf(check.get("status"));
            if ("ok".equals(status)) {
                continue;
            }
            parts.add(finding(tenant, check));
            attention++;
        }
        String summary = attention > 0
                ? attention + " check point(s) need attention - run of " + time(started) + "."
                : "The results of the run of " + time(started) + ".";
        return send(tenant, task, recipients, subject, summary, Html.join(parts));
    }

    public boolean failed(Tenant tenant, TaskDefinition task, Instant started, String step, String message) {
        String summary = "The run of " + time(started) + " failed at the step \"" + step + "\" - it found nothing, and "
                + "the lines it was to read will be read again by the next run.";
        Html content = Html.join(List.of(LINE.fill(Map.of("text", "Why:", "color", NEUTRAL)),
                DETAIL.fill(Map.of("detail", message))));
        return send(tenant, task, task.notifyTo(), task.name() + ": run failed", summary, content);
    }

    public boolean unclear(Tenant tenant, TaskDefinition task, Instant started, List<Map<String, Object>> checks) {
        List<Html> parts = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            if ("unclear".equals(check.get("status"))) {
                parts.add(finding(tenant, check));
            }
        }
        String summary = "The run of " + time(started) + " could not settle every finding - look at these yourself.";
        return send(tenant, task, task.notifyTo(), task.name() + ": findings unclear", summary, Html.join(parts));
    }

    private Html finding(Tenant tenant, Map<String, Object> check) {
        String status = "unclear".equals(check.get("status")) ? "unclear"
                : String.valueOf(check.get("severity")).toLowerCase(Locale.ROOT);
        String color = switch (status) {
            case "error" -> ERROR;
            case "warning" -> WARNING;
            default -> NEUTRAL;
        };
        List<Html> lines = new ArrayList<>();
        lines.add(LINE.fill(Map.of("text", String.valueOf(check.get("note")), "color", color)));
        if (check.get("evidence") instanceof List<?> evidence) {
            int shown = 0;
            for (Object item : evidence) {
                if (shown++ >= MAX_EVIDENCE || !(item instanceof Map<?, ?> line)) {
                    continue;
                }
                String quoted = String.valueOf(line.get("text"));
                if (quoted.length() > MAX_EVIDENCE_LINE) {
                    quoted = quoted.substring(0, MAX_EVIDENCE_LINE) + " [...]";
                }
                lines.add(DETAIL.fill(Map.of("detail", line.get("file") + " " + line.get("line") + ": " + quoted)));
            }
        }
        return SECTION.fill(Map.of("title", check.get("name") + " (" + status + ")",
                "brand", branding.forTenant(tenant.id()).primaryColor(), "content", Html.join(lines)));
    }

    private boolean send(Tenant tenant, TaskDefinition task, List<String> recipients, String subject, String summary,
                         Html content) {
        Branding look = branding.forTenant(tenant.id());
        Html page = PAGE.fill(Map.of("subject", subject, "installation", look.name(), "brand", look.primaryColor(),
                "title", task.name(), "summary", summary, "content", content,
                "footer", FOOTER.fill(Map.of("task", task.name(), "tenant", tenant.name()))));
        try {
            sender.send(recipients, subject, page.value());
            return true;
        } catch (MessagingException | RuntimeException e) {
            LOG.warn("The mail of task {} of tenant {} could not be sent: {}", task.name(), tenant.id(), e.toString());
            return false;
        }
    }

    private String time(Instant instant) {
        return TIME.format(instant.atZone(zone));
    }
}
