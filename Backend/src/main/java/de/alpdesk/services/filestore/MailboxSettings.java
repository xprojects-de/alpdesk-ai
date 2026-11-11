package de.alpdesk.services.filestore;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What a mailbox store needs that is no secret - stored as JSON in file_store.settings; the password is the store's
 * secret (docs/architecture/file-stores.md "Mailbox").
 *
 * @param security SSL (IMAPS, 993), STARTTLS (143) - or NONE, only to a host in app.file-store.internal-hosts
 * @param mailbox  the mailbox folder the store stands for, e.g. INBOX
 * @param days     the messages of the last days listed - older ones stay in the mailbox unseen
 */
@Serdeable
public record MailboxSettings(String host, int port, String username, Security security, String mailbox, int days) {

    public enum Security {
        SSL, STARTTLS, NONE
    }

    /** Another server or account than before: the password is not sent to it unasked. */
    public boolean sameServer(MailboxSettings other) {
        return host.equals(other.host) && port == other.port && username.equals(other.username)
                && security == other.security;
    }

    /** The same messages as before: the same account and mailbox folder - fewer or more days list fewer or more. */
    public boolean sameFiles(MailboxSettings other) {
        return sameServer(other) && mailbox.equals(other.mailbox);
    }
}
