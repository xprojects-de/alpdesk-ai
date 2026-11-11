package de.alpdesk.controller.chat

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.common.ServiceException
import dev.langchain4j.data.message.UserMessage
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Audio in the chat: stored with a small copy for the models (ffmpeg) and a transcript; read_file sends the copy to a
 * model that hears it, the transcript or a hint to the others. Videos: ChatVideoSpec.
 */
@Requires({ ChatMediaSpec.ffmpegAvailable() })
@MicronautTest(transactional = false)
class ChatMediaSpec extends Specification {

    static final Path STORAGE = Path.of("build/test-storage")

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    @Shared
    Map transcriber

    def setup() {
        api = api ?: new AiApi(client)
        transcriber = transcriber ?: api.chatModel("GEMINI", [readsAudio: true, transcribes: true])
        fake.reset()
    }

    def "audio gets a small copy and a transcript; a model that listens gets the audio, another one the transcript"() {
        given:
        def tenant = api.tenant()
        def listening = api.chatModel("GEMINI", [readsAudio: true])
        def deaf = api.chatModel("ANTHROPIC")
        def token = userWithKeys(tenant, listening, deaf, transcriber)

        when:
        def uploaded = api.chatUpload(token, ["anruf.wav": wav(2)])

        then: "stored as uploaded, with an MP3 for the models"
        uploaded.files[0].contentType.startsWith("audio/")
        def media = Files.walk(STORAGE.resolve("tenants/${tenant.id}/chat")).filter { it.fileName.toString() == "media" }.toList()
        media.size() == 1
        mp3(Files.readAllBytes(media[0]))

        and: "the transcriber wrote the transcript, recorded as usage"
        fake.chats.first().model == transcriber.modelName
        (api.call(token, "GET", "/tenant/admin/usage") as List<Map>).any { it.modelName == transcriber.modelName }

        when:
        def heard = api.ask(token, [conversationId: uploaded.conversation.id, modelId: listening.id, message: "Was sagt er?"])
        def asked = fake.chats.last().messages.find { it instanceof UserMessage && !FakeAiModels.isToolImages(it as UserMessage) } as UserMessage

        then: "the question says a transcript exists; the listening model gets the audio itself"
        (asked.contents().first() as dev.langchain4j.data.message.TextContent).text().contains("transcript available")
        heard.answer.content.endsWith("(Audio: 1)")

        when:
        def read = api.ask(token, [conversationId: uploaded.conversation.id, modelId: deaf.id, message: "Was sagt er?"])

        then: "the other one reads the transcript"
        read.answer.content.endsWith("(Dateien: anruf.wav)")
        def transcript = fake.chats.last().messages.find { it instanceof dev.langchain4j.data.message.ToolExecutionResultMessage }
        transcript.text().contains("Transkript der Aufnahme (1 Audio)")
    }

    def "nothing of an audio file is stored while it is transcribed, so a restart then leaves nothing behind"() {
        given:
        def tenant = api.tenant()
        def token = userWithKeys(tenant, transcriber)
        def chatFolder = STORAGE.resolve("tenants/${tenant.id}/chat")
        List<Path> storedWhileTranscribing = null
        fake.whileTranscribing = {
            storedWhileTranscribing = Files.exists(chatFolder) ? Files.walk(chatFolder).filter { Files.isRegularFile(it) }.toList() : []
        }

        when:
        def uploaded = api.chatUpload(token, ["anruf.wav": wav(1)])

        then: "transcribed before anything was stored, and stored with its transcript"
        storedWhileTranscribing == []
        Files.walk(chatFolder).filter { it.fileName.toString() == "media" }.count() == 1
        uploaded.files[0].contentType.startsWith("audio/")
    }

    def "without a transcript a model that cannot listen is told to have the user choose another one"() {
        given: "a tenant without a key for the transcriber's provider"
        def tenant = api.tenant()
        def deaf = api.chatModel("ANTHROPIC")
        api.allow(tenant.id, deaf, transcriber)
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-key-of-ANTHROPIC"])

        when:
        def uploaded = api.chatUpload(token, ["anruf.wav": wav(1)])
        def result = api.ask(token, [conversationId: uploaded.conversation.id, modelId: deaf.id, message: "Was sagt er?"])

        then:
        result.answer.content.contains("cannot listen to audio")
        result.answer.content.contains("has no transcript")
    }

    def "a transcriber that fails keeps the upload - the file is stored without a transcript"() {
        given:
        def tenant = api.tenant()
        def deaf = api.chatModel("ANTHROPIC")
        def token = userWithKeys(tenant, deaf, transcriber)
        fake.whileTranscribing = {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "provider.keyRefused", "OPENAI refused the API key")
        }

        when:
        def uploaded = api.chatUpload(token, ["anruf.wav": wav(1)])
        fake.whileTranscribing = null
        def result = api.ask(token, [conversationId: uploaded.conversation.id, modelId: deaf.id, message: "Was sagt er?"])

        then:
        uploaded.files[0].contentType.startsWith("audio/")
        result.answer.content.contains("has no transcript")
    }

    def "the transcriber writes no transcript for a tenant it is not assigned to"() {
        given: "a tenant with keys for every provider, but not allowed the transcriber"
        def tenant = api.tenant()
        def deaf = api.chatModel("ANTHROPIC")
        def token = userWithKeys(tenant, deaf)

        when:
        def uploaded = api.chatUpload(token, ["anruf.wav": wav(1)])
        def result = api.ask(token, [conversationId: uploaded.conversation.id, modelId: deaf.id, message: "Was sagt er?"])

        then: "its audio did not go to the transcriber"
        !fake.chats.any { it.model == transcriber.modelName }
        result.answer.content.contains("has no transcript")
    }

    private String userWithKeys(Map tenant, Map... models) {
        api.allow(tenant.id, models)
        def token = api.tenantAdminToken(tenant.id)
        ["ANTHROPIC", "OPENAI", "GEMINI"].each { api.call(token, "PUT", "/tenant/admin/provider-keys/${it}", [apiKey: "own-key-of-" + it]) }
        token
    }

    /** MP3 frames start with 0xFFF (or the file with an ID3 tag). */
    private static boolean mp3(byte[] bytes) {
        new String(bytes, 0, 3) == "ID3" || ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0)
    }

    /** A tone of the given seconds as a WAV, written by Java itself. */
    private static byte[] wav(int seconds) {
        def format = new AudioFormat(16000, 16, 1, true, false)
        byte[] samples = new byte[16000 * 2 * seconds]
        for (int i = 0; i < samples.length / 2; i++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * i / 16000) * 8000)
            samples[2 * i] = (byte) (value & 0xFF)
            samples[2 * i + 1] = (byte) ((value >> 8) & 0xFF)
        }
        def out = new ByteArrayOutputStream()
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(samples), format, samples.length / 2 as long),
                AudioFileFormat.Type.WAVE, out)
        out.toByteArray()
    }

    static boolean ffmpegAvailable() {
        try {
            new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
        } catch (IOException ignored) {
            false
        }
    }
}
