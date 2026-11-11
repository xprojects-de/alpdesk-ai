package de.alpdesk.mail;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.List;
import java.util.Properties;

/**
 * Sends HTML e-mails through the SMTP server configured in {@code app.mail.*}. Without a host it is
 * switched off: {@link #enabled()} is false and nothing is sent.
 */
@Singleton
public class MailSender {

    private static final String TIMEOUT_MILLIS = "10000";

    private final Session session;
    private final String from;

    public MailSender(@Value("${app.mail.host:}") String host,
                      @Value("${app.mail.port:587}") int port,
                      @Value("${app.mail.security:STARTTLS}") String security,
                      @Value("${app.mail.username:}") String username,
                      @Value("${app.mail.password:}") String password,
                      @Value("${app.mail.from:}") String from) {
        this.session = host.isBlank() ? null : session(host, port, security, username, password);
        this.from = from.isBlank() ? username : from;
        if (session != null) {
            requireAddress(this.from);
        }
    }

    public boolean enabled() {
        return session != null;
    }

    public void send(List<String> recipients, String subject, String html) throws MessagingException {
        if (!enabled()) {
            throw new IllegalStateException("No SMTP server configured (app.mail.host)");
        }
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(String.join(",", recipients)));
        message.setSubject(subject, "UTF-8");
        message.setContent(html, "text/html; charset=UTF-8");
        Transport.send(message);
    }

    /** Checked at startup: a mail server without a valid sender would fail every mail later. */
    private static void requireAddress(String from) {
        try {
            new InternetAddress(from, true);
        } catch (AddressException e) {
            throw new IllegalArgumentException("app.mail.from (or app.mail.username) must be an e-mail address, not '"
                    + from + "'", e);
        }
    }

    private static Session session(String host, int port, String security, String username, String password) {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", String.valueOf(port));
        properties.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        switch (security.toUpperCase()) {
            case "STARTTLS" -> {
                properties.put("mail.smtp.starttls.enable", "true");
                properties.put("mail.smtp.starttls.required", "true");
            }
            case "SSL" -> properties.put("mail.smtp.ssl.enable", "true");
            case "NONE" -> { }
            default -> throw new IllegalArgumentException("app.mail.security must be STARTTLS, SSL or NONE, not " + security);
        }
        if (username.isBlank()) {
            return Session.getInstance(properties);
        }
        properties.put("mail.smtp.auth", "true");
        return Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
    }
}
