package de.alpdesk.services.task

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import de.alpdesk.services.ai.AiApi
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import jakarta.mail.internet.MimeMessage

/**
 * The HTTP steps of the task specs (TaskSpec, TaskRunSpec), on top of AiApi: a tenant that may have tasks, with a chat
 * model, keys and the store "Logdateien" (FakeStoreClient, folder /logs). Mails go to one GreenMail of the test JVM -
 * the specs' context points app.mail.* at it (PROPERTIES).
 */
class TaskApi {

    static final String TASKS = "/tenant/admin/tasks"
    static final int SMTP_PORT = 39525
    static final String LOGS = "/logs"

    private static GreenMail mailbox

    final AiApi api

    TaskApi(AiApi api) {
        this.api = api
    }

    static synchronized GreenMail mailbox() {
        if (mailbox == null) {
            mailbox = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
            mailbox.start()
        }
        mailbox
    }

    /** The mails that went to this address. */
    static List<MimeMessage> mailsTo(String address) {
        mailbox().receivedMessages.findAll { message -> message.allRecipients*.toString().contains(address) }
    }

    /** [tenant, token (a tenant admin), model, store] - the tenant may have tasks. */
    Map tenant(boolean tasksAllowed = true) {
        def tenant = api.tenant()
        if (tasksAllowed) {
            allowTasks(tenant, true)
        }
        def model = api.chatModel()
        api.allow(tenant.id, model)
        def token = api.tenantAdminToken(tenant.id)
        ["ANTHROPIC", "OPENAI", "GEMINI"].each { api.call(token, "PUT", "/tenant/admin/provider-keys/${it}", [apiKey: "own-key-of-" + it]) }
        def store = api.admin("POST", "/admin/tenants/${tenant.id}/file-stores", [name: "Logdateien", type: "SFTP",
                host: "localhost", port: 22, username: "check", password: "check-secret", basePath: LOGS]) as Map
        api.admin("PUT", "/admin/tenants/${tenant.id}/file-stores/${store.id}/fingerprint", [fingerprint: FakeStoreClient.FINGERPRINT])
        [tenant: tenant, token: token, model: model, store: store]
    }

    /** The store "Logdateien" lets tasks write - its server, folder and confirmed key unchanged. */
    void allowWriting(Map setup, boolean allowed = true) {
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}/file-stores/${setup.store.id}", [name: "Logdateien", type: "SFTP",
                host: "localhost", port: 22, username: "check", basePath: LOGS, taskWrite: allowed])
    }

    void allowTasks(Map tenant, boolean allowed) {
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, tasksAllowed: allowed])
    }

    /** The log check of the plan: read the day's logs, check, verify, mail on findings. */
    static String logCheck(Map model, String recipient, Map options = [:]) {
        String find = options.find ?: "Timeout"
        String verifyRounds = options.verifyRounds ?: 2
        """\
        version: 1
        name: ${options.name ?: "Log check " + UUID.randomUUID()}
        # who hears of a failed or unclear run
        notify: [${options.notify ?: recipient}]
        steps:
          - id: logs
            type: read_files
            store: Logdateien
            pattern: "{yyyy-MM-dd}_*.log"
            days: 2
            include: [ERROR, WARN]
          - id: check
            type: agent
            model: ${model.displayName}
            input: [logs]
            checks:
              - name: Interface
                severity: error
                instruction: Were there timeouts to interface X? [[find ${find}]]${options.checkMarker ?: ""}
          - id: verify
            type: verify
            of: check
            next:
              - if: verify.unclear > 0
                to: check
                maxRounds: ${verifyRounds}
          - id: mail
            type: send_mail
            if: verify.errors > 0 || verify.warnings > 0
            input: [verify]
            to: [${recipient}]
            subject: Findings in the logs
        """.stripIndent()
    }

    Map create(String token, String source, Boolean active = null) {
        api.call(token, "POST", TASKS, [source: source, active: active]) as Map
    }

    Map validate(String token, String source) {
        api.call(token, "POST", "${TASKS}/validate", [source: source]) as Map
    }

    Object start(String token, Object taskId) {
        (api.call(token, "POST", "${TASKS}/${taskId}/runs", [:]) as Map).runId
    }

    /** [status, code] of a refused call. */
    List refusal(String token, String method, String path, Map body = null) {
        try {
            def request = HttpRequest.create(HttpMethod.valueOf(method), path).bearerAuth(token)
            if (body != null || method in ["POST", "PUT"]) {
                request = request.body(body ?: [:])
            }
            api.client.toBlocking().exchange(request)
            throw new AssertionError("$method $path was not refused")
        } catch (HttpClientResponseException e) {
            [e.status.code, e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }

    /** The run once it ended. */
    Map awaitRun(String token, Object taskId, Object runId) {
        long deadline = System.currentTimeMillis() + 20_000
        while (true) {
            Map run = api.call(token, "GET", "${TASKS}/${taskId}/runs/${runId}") as Map
            if (!((run.run as Map).state in ["QUEUED", "RUNNING"]) || System.currentTimeMillis() > deadline) {
                return run
            }
            Thread.sleep(100)
        }
    }
}
