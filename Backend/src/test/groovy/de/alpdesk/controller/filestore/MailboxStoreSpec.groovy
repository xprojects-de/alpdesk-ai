package de.alpdesk.controller.filestore

import com.icegreen.greenmail.imap.ImapHostManager
import com.icegreen.greenmail.store.MailFolder
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.document.SourceApi
import de.alpdesk.services.filestore.FileStoreService
import de.alpdesk.services.task.FakeStoreClient
import de.alpdesk.services.task.TaskApi
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A mailbox as a file store (docs/architecture/file-stores.md "Mailbox"): GreenMail serves IMAP; every message of the
 * last days is a folder, its attachments and mail.txt the files, an embedded logo no file, a forwarded message unpacked
 * - and nothing in the mailbox changes, not even \Seen. Unencrypted IMAP only to 127.0.0.1, an internal host here.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class MailboxStoreSpec extends Specification {

    static final int IMAP_PORT = 39143
    static final String STORES = "/tenant/admin/file-stores"

    @Shared
    GreenMail imap = new GreenMail(new ServerSetup(IMAP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_IMAP))

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FileStoreService service

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient fakeStore

    @Shared
    AiApi api

    @Shared
    TaskApi tasks

    def setupSpec() {
        imap.start()
    }

    def cleanupSpec() {
        imap.stop()
    }

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        TaskApi.mailbox()
        models.reset()
        fakeStore.reset()
    }

    def "a mailbox store is saved and checked like the others - its password never comes back, tasks never write into it"() {
        given:
        def mailbox = account()
        def admin = api.tenantAdminToken(api.tenant().id)
        deliver(mailbox, invoiceMail("rechnung.txt", "date: 2026-03-14"), daysAgo(1))

        when:
        def store = api.call(admin, "POST", STORES, imapStore(mailbox)) as Map
        def check = api.call(admin, "POST", "${STORES}/${store.id}/check") as Map

        then:
        store.type == "IMAP"
        store.security == "NONE"
        store.mailbox == "INBOX"
        store.days == 365
        store.taskWrite == false
        !store.containsKey("password")
        check.status == "OK"
        (check.entries as List).size() == 1
        (check.entries as List)[0] ==~ /\d{4}-\d{2}-\d{2}_\d+/
        (api.call(admin, "GET", "${STORES}/${store.id}/folders") as Map).folders == []
        refusal(admin, "POST", STORES, imapStore(mailbox) + [name: "Schreibend", taskWrite: true]) ==
                [400, "fileStore.mailboxReadOnly"]
    }

    def "unencrypted IMAP goes only to an internal host; a wrong password fails the check"() {
        given:
        def mailbox = account()
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        refusal(admin, "POST", STORES, imapStore(mailbox) + [host: "mail.example.com"]) == [400, "fileStore.plainImap"]

        when:
        def store = api.call(admin, "POST", STORES, imapStore(mailbox) + [password: "falsch"]) as Map
        def check = api.call(admin, "POST", "${STORES}/${store.id}/check") as Map

        then:
        check.status == "FAILED"
        check.errorCode == "fileStore.loginFailed"
    }

    def "every message of the last days is a folder - its attachments and mail.txt the files, an embedded logo none, a forwarded message unpacked; nothing is marked seen"() {
        given:
        def mailbox = account()
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        deliver(mailbox, invoiceMail("rechnung.txt", "date: 2026-03-14"), daysAgo(2))
        deliver(mailbox, forwardedMail(), daysAgo(1))
        deliver(mailbox, invoiceMail("alt.txt", "date: 2024-01-01"), daysAgo(400))
        def store = checked(admin, imapStore(mailbox))

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def messages = session.list("/")
        def first = session.list("/" + messages[0].name())
        def second = session.list("/" + messages[1].name())
        def attachment = new String(session.readWhole("/" + messages[0].name() + "/rechnung.txt", first.find { it.name() == "rechnung.txt" }.size(), 1_000_000), "UTF-8")
        def text = new String(session.readWhole("/" + messages[1].name() + "/mail.txt", second.find { it.name() == "mail.txt" }.size(), 1_000_000), "UTF-8")
        session.close()

        then: "the message of 400 days ago is not listed"
        messages.size() == 2
        messages.every { it.folder() }
        first*.name() == ["mail.txt", "rechnung.txt"]
        attachment == "date: 2026-03-14"

        and: "the forwarded message's attachment is a file, its text in mail.txt; the logo embedded in the HTML is none"
        second*.name() == ["lieferschein.txt", "mail.txt"]
        text.contains("Subject: Fwd: Lieferung")
        text.contains("--- Forwarded message ---")
        text.contains("Die Lieferung kommt am Montag.")

        and:
        inbox(mailbox).messages.every { !it.flags.contains(Flags.Flag.SEEN) }
    }

    @Timeout(30)
    def "a mail whose HTML is made to slow the reading down is read at once - script and style left out"() {
        given:
        def mailbox = account()
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        deliver(mailbox, htmlMail("<p>Der Text</p><script>weg()</script>" + "<" * 200_000 + "\f" * 200_000
                + "<style>" * 20_000), daysAgo(1))
        def store = checked(admin, imapStore(mailbox))

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def message = session.list("/")[0].name()
        def files = session.list("/" + message)
        def text = new String(session.readWhole("/" + message + "/mail.txt", files.find { it.name() == "mail.txt" }.size(), 5_000_000), "UTF-8")
        session.close()

        then:
        text.contains("Der Text")
        !text.contains("weg()")
        !text.contains("style")
    }

    def "the password goes out only over a connection the server proved itself on"() {
        given: "the same account on a server with a self-signed certificate"
        def mailbox = account()
        def admin = api.tenantAdminToken(api.tenant().id)
        def imaps = new GreenMail(new ServerSetup(IMAP_PORT + 1, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS))
        imaps.start()
        imaps.setUser(mailbox.address as String, mailbox.user as String, "secret")

        when: "STARTTLS is asked of a server that offers none - the right password would log in"
        def plain = api.call(admin, "POST", STORES, imapStore(mailbox) + [security: "STARTTLS"]) as Map
        def withoutTls = api.call(admin, "POST", "${STORES}/${plain.id}/check") as Map

        then:
        withoutTls.status == "FAILED"

        when: "a certificate nobody vouches for"
        def ssl = api.call(admin, "POST", STORES, imapStore(mailbox) + [security: "SSL", port: IMAP_PORT + 1]) as Map
        def untrusted = api.call(admin, "POST", "${STORES}/${ssl.id}/check") as Map

        then:
        untrusted.status == "FAILED"
        untrusted.errorCode == "fileStore.tlsFailed"

        cleanup:
        imaps.stop()
    }

    def "a mailbox is never written to"() {
        given:
        def mailbox = account()
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = checked(admin, imapStore(mailbox))
        def session = service.open(tenant.id as Long, store.id as Long)

        when:
        session.write("/x.csv", "a".bytes)

        then:
        def e = thrown(ServiceException)
        e.code() == "fileStore.notWritable"

        cleanup:
        session?.close()
    }

    def "a task reads the attachments of mailed documents into rows - the next run reads none again"() {
        given:
        def mailbox = account()
        def setup = tasks.tenant()
        deliver(mailbox, invoiceMail("rechnung.txt", "date: 2026-03-14"), daysAgo(2))
        deliver(mailbox, forwardedMail(), daysAgo(1))
        checked(setup.token, imapStore(mailbox) + [name: "Postfach"])
        def task = tasks.create(setup.token, """\
            version: 1
            name: Belege aus dem Postfach
            notify: [it@example.com]
            steps:
              - id: documents
                type: read_documents
                store: Postfach
                subfolders: true
                pattern: ["rechnung*", "lieferschein*", "*.png"]
              - id: rows
                type: extract
                model: ${setup.model.displayName}
                input: documents
                fields:
                  - {name: date, type: date}
            """.stripIndent())

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def again = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def documents = ((run.steps as List<Map>)[0].result as Map).documents as List<Map>
        documents*.name.collect { it.toString().substring(it.toString().indexOf('/') + 1) }.sort() == ["lieferschein.txt", "rechnung.txt"]
        (run.rows as List<Map>)*.values.sort { it.date } == [[date: "2026-03-14"], [date: "2026-03-20"]]
        ((again.steps as List<Map>)[0].result as Map).files == 0
    }

    def "a knowledge source syncs a mailbox: the attachments and every message's mail.txt become documents"() {
        given:
        def mailbox = account()
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        deliver(mailbox, invoiceMail("rechnung.txt", "date: 2026-03-14"), daysAgo(2))
        def store = checked(admin, imapStore(mailbox))
        def sources = new SourceApi(api, null)

        when:
        def source = sources.source([token: admin, store: store], [folder: ""])
        sources.run(admin, source.id)
        def documents = sources.documents(admin, source.id)

        then:
        documents.keySet().collect { it.substring(it.indexOf('/') + 1) }.sort() == ["mail.txt", "rechnung.txt"]
        documents.values().every { it.status == "READY" }
    }

    /** A user of its own for every feature: belege-<n>@example.com, password "secret". */
    private Map account() {
        String name = "belege-" + UUID.randomUUID().toString().substring(0, 8)
        imap.setUser(name + "@example.com", name, "secret")
        [user: name, address: name + "@example.com"]
    }

    private static Map imapStore(Map mailbox) {
        [name: "Postfach " + UUID.randomUUID(), type: "IMAP", host: "127.0.0.1", port: IMAP_PORT, username: mailbox.user,
         password: "secret", security: "NONE"]
    }

    private Map checked(String token, Map settings) {
        def store = api.call(token, "POST", STORES, settings) as Map
        assert (api.call(token, "POST", "${STORES}/${store.id}/check") as Map).status == "OK"
        store
    }

    private MailFolder inbox(Map mailbox) {
        ImapHostManager manager = imap.managers.imapHostManager
        manager.getInbox(imap.managers.userManager.getUser(mailbox.user as String))
    }

    /** Into the mailbox with this internal date - what IMAP's SINCE compares. */
    private void deliver(Map mailbox, MimeMessage message, Date received) {
        inbox(mailbox).appendMessage(message, new Flags(), received)
    }

    private static Date daysAgo(int days) {
        Date.from(Instant.now().minus(days, ChronoUnit.DAYS))
    }

    /** A supplier's mail: a text, an attachment, and an HTML part with a logo embedded by its Content-ID. */
    private static MimeMessage invoiceMail(String fileName, String content) {
        def message = new MimeMessage(Session.getInstance(new Properties()))
        message.setFrom(new InternetAddress("rechnung@lieferant.example.com"))
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("belege@example.com"))
        message.setSubject("Ihre Rechnung")
        def related = new MimeMultipart("related")
        related.addBodyPart(new MimeBodyPart().tap { setText("<p>Anbei die Rechnung</p><img src=\"cid:logo\">", "UTF-8", "html") })
        related.addBodyPart(new MimeBodyPart().tap {
            setContent([0x89, 0x50, 0x4E, 0x47] as byte[], "image/png")
            setFileName("logo.png")
            setContentID("<logo>")
            setDisposition(INLINE)
        })
        def alternative = new MimeMultipart("alternative")
        alternative.addBodyPart(new MimeBodyPart().tap { setText("Anbei die Rechnung", "UTF-8") })
        alternative.addBodyPart(new MimeBodyPart().tap { setContent(related) })
        def mixed = new MimeMultipart("mixed")
        mixed.addBodyPart(new MimeBodyPart().tap { setContent(alternative) })
        mixed.addBodyPart(new MimeBodyPart().tap {
            setText(content, "UTF-8")
            setFileName(fileName)
            setDisposition(ATTACHMENT)
        })
        message.setContent(mixed)
        message.saveChanges()
        message
    }

    private static MimeMessage htmlMail(String html) {
        def message = new MimeMessage(Session.getInstance(new Properties()))
        message.setFrom(new InternetAddress("irgendwer@example.com"))
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("belege@example.com"))
        message.setSubject("Hallo")
        message.setText(html, "UTF-8", "html")
        message.saveChanges()
        message
    }

    /** A mail forwarded as an attachment: the enclosed message carries the delivery note. */
    private static MimeMessage forwardedMail() {
        def inner = new MimeMessage(Session.getInstance(new Properties()))
        inner.setFrom(new InternetAddress("versand@lieferant.example.com"))
        inner.setSubject("Lieferung")
        def innerParts = new MimeMultipart("mixed")
        innerParts.addBodyPart(new MimeBodyPart().tap { setText("Die Lieferung kommt am Montag.", "UTF-8") })
        innerParts.addBodyPart(new MimeBodyPart().tap {
            setText("date: 2026-03-20", "UTF-8")
            setFileName("lieferschein.txt")
            setDisposition(ATTACHMENT)
        })
        inner.setContent(innerParts)
        inner.saveChanges()
        def message = new MimeMessage(Session.getInstance(new Properties()))
        message.setFrom(new InternetAddress("mitarbeiter@example.com"))
        message.setSubject("Fwd: Lieferung")
        def parts = new MimeMultipart("mixed")
        parts.addBodyPart(new MimeBodyPart().tap { setText("Zur Ablage", "UTF-8") })
        parts.addBodyPart(new MimeBodyPart().tap { setContent(inner, "message/rfc822") })
        message.setContent(parts)
        message.saveChanges()
        message
    }

    private List refusal(String token, String method, String path, Map body) {
        tasks.refusal(token, method, path, body)
    }
}
