package de.alpdesk.services.filestore;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mailbox read as a store (docs/architecture/file-stores.md "Mailbox"): IMAP through Angus Mail, the folder opened read
 * only - no message is deleted, moved or flagged, not even as seen. Every message of the last days is a folder named by
 * its date and UID ("2026-03-14_4711" - a UID is stable within a mailbox folder, so the path is); its attachments are
 * the files in it, and mail.txt its sender, recipients, date, subject and text. A message forwarded as an attachment is
 * unpacked one level: its attachments are files too, its text goes into mail.txt. Parts embedded in an HTML text (a
 * signature's logo, inside multipart/related) are no files.
 */
@Singleton
public class MailboxStoreClient {

    static final String MAIL_TEXT = "mail.txt";
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 60_000;
    /** The names a check shows. */
    private static final int CHECK_NAMES = 50;
    private static final Pattern BLOCK_START = Pattern.compile("(?i)<(script|style)\\b");
    private static final Pattern SCRIPT_END = Pattern.compile("(?i)</script\\s*>");
    private static final Pattern STYLE_END = Pattern.compile("(?i)</style\\s*>");

    private final FileStoreAddresses addresses;

    public MailboxStoreClient(FileStoreAddresses addresses) {
        this.addresses = addresses;
    }

    /** Logs in, opens the mailbox folder and names the newest messages of the last days. */
    public CheckResult check(MailboxSettings settings, String password) {
        try (MailboxSession session = open(settings, password)) {
            List<RemoteFile> messages = session.list("/");
            List<String> names = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0 && names.size() < CHECK_NAMES; i--) {
                names.add(messages.get(i).name());
            }
            return CheckResult.ok(null, names);
        }
    }

    public MailboxSession open(MailboxSettings settings, String password) {
        addresses.resolveAllowed(settings.host());
        if (settings.security() == MailboxSettings.Security.NONE && !addresses.plainAllowed(settings.host())) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.plainImap", "Unencrypted IMAP goes only to the "
                    + "hosts in APP_FILE_STORE_INTERNAL_HOSTS - use SSL or STARTTLS", Map.of("host", settings.host()));
        }
        String protocol = settings.security() == MailboxSettings.Security.SSL ? "imaps" : "imap";
        Properties properties = new Properties();
        properties.put("mail." + protocol + ".connectiontimeout", String.valueOf(CONNECT_TIMEOUT));
        properties.put("mail." + protocol + ".timeout", String.valueOf(READ_TIMEOUT));
        properties.put("mail." + protocol + ".writetimeout", String.valueOf(READ_TIMEOUT));
        if (settings.security() == MailboxSettings.Security.SSL) {
            properties.put("mail.imaps.ssl.checkserveridentity", "true");
        } else if (settings.security() == MailboxSettings.Security.STARTTLS) {
            properties.put("mail.imap.starttls.enable", "true");
            properties.put("mail.imap.starttls.required", "true");
            properties.put("mail.imap.ssl.checkserveridentity", "true");
        }
        Store store = null;
        try {
            store = Session.getInstance(properties).getStore(protocol);
            store.connect(settings.host(), settings.port(), settings.username(), password);
            Folder folder = store.getFolder(settings.mailbox());
            folder.open(Folder.READ_ONLY);
            return new MailboxSession(store, folder, settings.days());
        } catch (MessagingException e) {
            closeQuietly(store);
            throw failure(settings, e);
        }
    }

    private static ServiceException failure(MailboxSettings settings, MessagingException e) {
        if (e instanceof AuthenticationFailedException) {
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.loginFailed", "The mail server refused the "
                    + "user name or password");
        }
        if (e instanceof FolderNotFoundException) {
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound", "The mailbox has no folder "
                    + settings.mailbox(), Map.of("path", settings.mailbox()));
        }
        if (causedBy(e, SSLException.class)) {
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.tlsFailed", "The TLS connection to "
                    + settings.host() + " failed: " + e.getMessage(), Map.of("host", settings.host()));
        }
        if (causedBy(e, ConnectException.class) || causedBy(e, UnknownHostException.class)
                || causedBy(e, SocketTimeoutException.class)) {
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.unreachable", "The server " + settings.host()
                    + " could not be reached", Map.of("host", settings.host()));
        }
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.readFailed", "Reading the mailbox failed: "
                + e.getMessage(), Map.of("path", settings.mailbox()));
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(@Nullable Store store) {
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // nothing more to do with it
            }
        }
    }

    /** One open mailbox folder, read only. The message last listed or read is kept - its files are read in turns. */
    public static final class MailboxSession implements FileStoreSession {
        private final Store store;
        private final Folder folder;
        private final int days;
        private @Nullable Long currentUid;
        private Map<String, Attachment> currentFiles = Map.of();
        private @Nullable String currentFile;
        private byte[] currentBytes = new byte[0];

        MailboxSession(Store store, Folder folder, int days) {
            this.store = store;
            this.folder = folder;
            this.days = days;
        }

        /** "/" the messages of the last days, as folders; "/<message>" its files. */
        @Override
        public List<RemoteFile> list(String path) {
            String relative = path.replaceAll("^/+|/+$", "");
            try {
                if (relative.isEmpty()) {
                    return messages();
                }
                if (relative.contains("/")) {
                    throw notFound(path);
                }
                List<RemoteFile> files = new ArrayList<>();
                for (Map.Entry<String, Attachment> file : filesOf(relative).entrySet()) {
                    files.add(new RemoteFile(file.getKey(), file.getValue().size(), file.getValue().received(), false));
                }
                files.sort(Comparator.comparing(RemoteFile::name));
                return files;
            } catch (MessagingException | IOException e) {
                throw StoreFailures.readFailed(path, e instanceof IOException io ? io : new IOException(e.getMessage()));
            }
        }

        @Override
        public byte[] read(String path, long offset, int maxBytes) {
            String relative = path.replaceAll("^/+", "");
            int slash = relative.indexOf('/');
            if (slash < 0) {
                throw notFound(path);
            }
            try {
                Map<String, Attachment> files = filesOf(relative.substring(0, slash));
                String name = relative.substring(slash + 1);
                Attachment file = files.get(name);
                if (file == null) {
                    throw notFound(path);
                }
                if (!path.equals(currentFile)) {
                    currentBytes = file.bytes();
                    currentFile = path;
                }
                if (offset >= currentBytes.length || maxBytes <= 0) {
                    return new byte[0];
                }
                return Arrays.copyOfRange(currentBytes, (int) offset, (int) Math.min(currentBytes.length, offset + maxBytes));
            } catch (MessagingException | IOException e) {
                throw StoreFailures.readFailed(path, e instanceof IOException io ? io : new IOException(e.getMessage()));
            }
        }

        /** A mailbox is read only: tasks never write into it. */
        @Override
        public void write(String path, byte[] content) {
            throw ServiceException.conflict("fileStore.notWritable", "A mailbox is read only - a task writes into another "
                    + "file store", Map.of("path", path));
        }

        @Override
        public void close() {
            try {
                folder.close(false);
            } catch (MessagingException ignored) {
                // the store below is closed next
            }
            closeQuietly(store);
        }

        private List<RemoteFile> messages() throws MessagingException {
            Date since = Date.from(LocalDate.now(ZoneOffset.UTC).minusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant());
            Message[] found = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, since));
            if (found.length > MAX_ENTRIES) {
                throw StoreFailures.tooManyEntries("/");
            }
            FetchProfile profile = new FetchProfile();
            profile.add(UIDFolder.FetchProfileItem.UID);
            profile.add(FetchProfile.Item.ENVELOPE);
            // every message's structure in the same command: a task or a source walking the messages lists each
            // one's attachments next - without it, one round trip per message
            profile.add(FetchProfile.Item.CONTENT_INFO);
            folder.fetch(found, profile);
            List<RemoteFile> messages = new ArrayList<>();
            for (Message message : found) {
                Instant received = received(message);
                String name = received.atZone(ZoneOffset.UTC).toLocalDate() + "_" + ((UIDFolder) folder).getUID(message);
                messages.add(new RemoteFile(name, 0, received, true));
            }
            messages.sort(Comparator.comparing(RemoteFile::name));
            return messages;
        }

        /** The files of a message by name - kept while the next call is about the same message. */
        private Map<String, Attachment> filesOf(String messageName) throws MessagingException, IOException {
            int underscore = messageName.lastIndexOf('_');
            long uid;
            try {
                uid = Long.parseLong(messageName.substring(underscore + 1));
            } catch (NumberFormatException e) {
                throw notFound("/" + messageName);
            }
            if (currentUid != null && currentUid == uid) {
                return currentFiles;
            }
            Message message = ((UIDFolder) folder).getMessageByUID(uid);
            if (message == null) {
                throw notFound("/" + messageName);
            }
            if (forwardsMessage(message)) {
                // a forwarded message is read from one copy of the whole mail, parsed here: a server's answers for the
                // parts of a nested message, fetched one by one, were seen empty (GreenMail, 4 November 2026). Only
                // such mails are fetched whole - the others are listed from their structure, nothing downloaded.
                message = new MimeMessage((MimeMessage) message);
            }
            Instant received = received(message);
            Map<String, Attachment> files = new LinkedHashMap<>();
            MailText text = new MailText();
            describe(message, text.text);
            collect(message, files, text, received, false, 0);
            byte[] mailText = text.text.toString().strip().concat("\n").getBytes(StandardCharsets.UTF_8);
            files.put(MAIL_TEXT, new Attachment(null, mailText, mailText.length, received));
            currentUid = uid;
            currentFiles = files;
            currentFile = null;
            return files;
        }

        /**
         * The message's parts: a part with a file name is a file - unless it is embedded in an HTML text (a part of
         * multipart/related after its first); the first text part goes into mail.txt; a forwarded message (one level)
         * gives its own files and text.
         */
        private void collect(Part part, Map<String, Attachment> files, MailText text, Instant received,
                             boolean embedded, int depth) throws MessagingException, IOException {
            if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
                boolean related = part.isMimeType("multipart/related");
                for (int i = 0; i < multipart.getCount(); i++) {
                    collect(multipart.getBodyPart(i), files, text, received, embedded || related && i > 0, depth);
                }
                return;
            }
            if (part.isMimeType("message/rfc822") && depth == 0 && part.getContent() instanceof Message forwarded) {
                text.text.append("\n\n--- Forwarded message ---\n");
                describe(forwarded, text.text);
                text.bodyFound = false;
                collect(forwarded, files, text, received, false, depth + 1);
                return;
            }
            String name = fileName(part);
            if (name != null) {
                if (!embedded) {
                    files.put(unique(files, name), new Attachment(part, null, Math.max(0, part.getSize()), received));
                }
                return;
            }
            if (!text.bodyFound && part.isMimeType("text/plain")) {
                text.text.append('\n').append(String.valueOf(part.getContent()).strip()).append('\n');
                text.bodyFound = true;
            } else if (!text.bodyFound && part.isMimeType("text/html")) {
                text.text.append('\n').append(withoutTags(String.valueOf(part.getContent()))).append('\n');
                text.bodyFound = true;
            }
        }

        /** Whether the mail carries a message forwarded as an attachment - told by its structure alone. */
        private static boolean forwardsMessage(Message message) throws MessagingException, IOException {
            if (!message.isMimeType("multipart/*") || !(message.getContent() instanceof Multipart parts)) {
                return false;
            }
            for (int i = 0; i < parts.getCount(); i++) {
                if (parts.getBodyPart(i).isMimeType("message/rfc822")) {
                    return true;
                }
            }
            return false;
        }

        /** mail.txt as it grows: the headers, then the first text of each message (its own, a forwarded one's). */
        private static final class MailText {
            final StringBuilder text = new StringBuilder();
            boolean bodyFound;
        }

        private static void describe(Message message, StringBuilder text) throws MessagingException {
            text.append("From: ").append(addresses(message.getFrom())).append('\n')
                    .append("To: ").append(addresses(message.getRecipients(Message.RecipientType.TO))).append('\n')
                    .append("Date: ").append(received(message)).append('\n')
                    .append("Subject: ").append(message.getSubject() == null ? "" : message.getSubject()).append('\n');
        }

        private static String addresses(@Nullable Address[] addresses) {
            if (addresses == null) {
                return "";
            }
            return String.join(", ", Arrays.stream(addresses).map(Address::toString).toList());
        }

        private static Instant received(Message message) throws MessagingException {
            Date date = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
            return date == null ? Instant.EPOCH : date.toInstant().truncatedTo(ChronoUnit.SECONDS);
        }

        /** The part's file name, decoded, without folders - null for a part without one. */
        private static @Nullable String fileName(Part part) throws MessagingException {
            String name = part.getFileName();
            if (name == null || name.isBlank()) {
                return null;
            }
            try {
                name = MimeUtility.decodeText(name);
            } catch (UnsupportedEncodingException e) {
                // kept as it came
            }
            name = name.replace('/', '_').replace('\\', '_').strip();
            return name.isEmpty() || RemoteFile.isDotEntry(name) || name.equals(MAIL_TEXT) ? "attachment" : name;
        }

        /** Two attachments of one name: the second gets "(2)" before its extension. */
        private static String unique(Map<String, Attachment> files, String name) {
            if (!files.containsKey(name)) {
                return name;
            }
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : "";
            for (int i = 2; ; i++) {
                String candidate = base + " (" + i + ")" + extension;
                if (!files.containsKey(candidate)) {
                    return candidate;
                }
            }
        }

        /**
         * A mail's HTML as text. Anyone can send a mail, so every step is linear in its length: a tag has no "<"
         * inside (a run of "<" is no tag), and whitespace other than line breaks is one blank before lines are trimmed.
         */
        private static String withoutTags(String html) {
            return withoutBlocks(html).replaceAll("<[^<>]++>", " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\s*\\n\\s*", "\n").strip();
        }

        /** Without script and style and what they hold - one never closed takes the rest of the mail with it. */
        private static String withoutBlocks(String html) {
            StringBuilder kept = new StringBuilder();
            Matcher start = BLOCK_START.matcher(html);
            int from = 0;
            while (start.find(from)) {
                kept.append(html, from, start.start()).append(' ');
                Matcher end = (start.group(1).equalsIgnoreCase("script") ? SCRIPT_END : STYLE_END).matcher(html);
                if (!end.find(start.end())) {
                    return kept.toString();
                }
                from = end.end();
            }
            return kept.append(html, from, html.length()).toString();
        }

        private static ServiceException notFound(String path) {
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound", "The mailbox has no "
                    + path.replaceAll("^/+", "") + " - the message was moved or deleted", Map.of("path", path));
        }
    }

    /**
     * A file of a message: an attachment's part (read when asked for) or mail.txt's text.
     *
     * @param size the attachment's size as the server tells it (encoded, a little larger) - only to see a change
     */
    record Attachment(@Nullable Part part, byte @Nullable [] text, long size, Instant received) {

        byte[] bytes() throws MessagingException, IOException {
            if (text != null) {
                return text;
            }
            try (var in = part.getInputStream()) {
                return in.readAllBytes();
            }
        }
    }

}
