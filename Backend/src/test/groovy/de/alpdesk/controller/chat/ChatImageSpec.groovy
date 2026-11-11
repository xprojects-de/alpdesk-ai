package de.alpdesk.controller.chat

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import dev.langchain4j.data.message.SystemMessage
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Images in the chat (docs/roadmap.md point 25): the chat's model creates them with the tool create_image and the
 * tenant's image model - a new one from the instruction, or one of the conversation's images changed or combined. The
 * result is a file of the conversation, improved round by round; every call is billed.
 */
@MicronautTest(transactional = false)
class ChatImageSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    de.alpdesk.services.chat.ChatFileCleanupJob cleanup

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a user asks for a new image without sending one: a file of the conversation, downloaded, billed"() {
        given:
        def setup = tenantWithImageModel()

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: "Mach mir eine Geburtstagseinladung [[create_image]]"])

        then: "the tool and its rule were offered, the image model got the instruction alone"
        fake.chats.first().tools.contains("create_image")
        def rules = (fake.chats.first().messages.first() as SystemMessage).text()
        rules.contains("it with create_image, whatever the rules")
        rules.contains("Keep text in the image short and large")
        rules.contains("the image model keeps whatever the instruction does not mention")
        fake.createdImages.size() == 1
        fake.createdImages[0].instruction == "Mach mir eine Geburtstagseinladung"
        fake.createdImages[0].images == []
        fake.createdImages[0].apiKey == "own-key-of-GEMINI"

        and: "the image is attached to the answer"
        result.answer.content.startsWith("Fertig: The image \"bild.png\" was created.")
        result.answer.files*.fileName == ["bild.png"]
        result.answer.files[0].contentType == "image/png"
        result.answer.files[0].origin == "GENERATED"
        result.answer.files[0].imageCount == 1

        when:
        def download = api.download(setup.token, "/tenant/chat/files/${result.answer.files[0].id}/content")

        then:
        download.status == HttpStatus.OK
        FakeAiModels.description(download.body()) == "Mach mir eine Geburtstagseinladung | images: 0"

        and: "the image model's call is billed as such"
        imageUsage(setup.tenant).calls == 1
        imageUsage(setup.tenant).inputTokens == 10
        imageUsage(setup.tenant).outputTokens == 1290
    }

    def "the image is improved round by round, each round on the last result as it is"() {
        given:
        def setup = tenantWithImageModel()
        def house = png(40, 30)
        def uploaded = api.chatUpload(setup.token, ["haus.png": house])
        def conversation = uploaded.conversation.id

        when:
        def first = api.ask(setup.token, [conversationId: conversation, modelId: setup.chat.id,
                                          message: "Hintergrund weiß [[create_image haus.png]]"])

        then: "the original went to the image model, not the chat model's JPEG copy"
        first.answer.files*.fileName == ["haus-v2.png"]
        fake.createdImages[0].images[0] == house

        when:
        def second = api.ask(setup.token, [conversationId: conversation, modelId: setup.chat.id,
                                           message: "Dach rot [[create_image haus-v2.png]]"])
        def v2 = api.download(setup.token, "/tenant/chat/files/${first.answer.files[0].id}/content").body()

        then:
        second.answer.files*.fileName == ["haus-v3.png"]
        fake.createdImages[1].images[0] == v2
        FakeAiModels.description(api.download(setup.token, "/tenant/chat/files/${second.answer.files[0].id}/content").body()) ==
                "Dach rot | images: 1"

        and: "the conversation lists all three"
        (api.call(setup.token, "GET", "/tenant/chat/conversations/${conversation}/files") as List)*.fileName ==
                ["haus.png", "haus-v2.png", "haus-v3.png"]
    }

    def "an image that expired after its retention time is no source for the image model - the model is told"() {
        given:
        def setup = tenantWithImageModel()
        def uploaded = api.chatUpload(setup.token, ["haus.png": png(40, 30)])
        cleanup.expire(java.time.Instant.now().plus(java.time.Duration.ofDays(15)))

        when:
        def answer = api.ask(setup.token, [conversationId: uploaded.conversation.id, modelId: setup.chat.id,
                                           message: "Hintergrund weiß [[create_image haus.png]]"])

        then:
        fake.createdImages.isEmpty()
        answer.answer.files.isEmpty()
        fake.chats.last().messages.findAll { it instanceof dev.langchain4j.data.message.ToolExecutionResultMessage }
                .any { (it as dev.langchain4j.data.message.ToolExecutionResultMessage).text()
                        .contains("expired - deleted after its retention time; ask the user to upload it again") }
    }

    def "a note of the history the model copies into its answer is left out"() {
        given:
        def setup = tenantWithImageModel()
        def first = api.ask(setup.token, [modelId: setup.chat.id, message: "Eine Karte [[create_image]]"])

        when:
        def second = api.ask(setup.token, [conversationId: first.conversation.id, modelId: setup.chat.id,
                                           message: "Nur eine Karte [[create_image bild.png]] [[repeat-note]]"])

        then: "the model saw the note on the earlier answer and repeated it - the stored answer has it no more"
        (fake.chats.last().messages.find { it instanceof dev.langchain4j.data.message.AiMessage } as dev.langchain4j.data.message.AiMessage)
                .text().contains("[Created with a tool: bild.png]")
        second.answer.content.startsWith("Fertig: The image \"bild-v2.png\" was created.")
        !second.answer.content.contains("[Created with")
        !(api.call(setup.token, "GET", "/tenant/chat/conversations/${first.conversation.id}/messages") as List<Map>)
                .last().content.contains("[Created with")
    }

    def "several images are combined in one call, in the order named"() {
        given:
        def setup = tenantWithImageModel()
        def house = png(40, 30)
        def meadow = png(60, 20)
        def uploaded = api.chatUpload(setup.token, ["wiese.png": meadow, "haus.png": house])

        when:
        def result = api.ask(setup.token, [conversationId: uploaded.conversation.id, modelId: setup.chat.id,
                                           message: "Setze das Haus auf die Wiese [[create_image haus.png wiese.png]]"])

        then:
        fake.createdImages[0].images == [house, meadow]
        result.answer.files*.fileName == ["haus-v2.png"]
        FakeAiModels.description(api.download(setup.token, "/tenant/chat/files/${result.answer.files[0].id}/content").body()) ==
                "Setze das Haus auf die Wiese | images: 2"
    }

    def "a large image goes as the model's JPEG copy - the image model takes about 20 MB per request"() {
        given:
        def setup = tenantWithImageModel()
        def large = noise(1100, 1100)
        assert large.length > 2_500_000
        def uploaded = api.chatUpload(setup.token, ["foto.png": large])

        when:
        api.ask(setup.token, [conversationId: uploaded.conversation.id, modelId: setup.chat.id, message: "Heller [[create_image foto.png]]"])

        then:
        def sent = fake.createdImages[0].images[0] as byte[]
        sent[0] == (byte) 0xFF && sent[1] == (byte) 0xD8
    }

    def "the image model's refusal reaches the chat's model - the call is billed all the same"() {
        given:
        def setup = tenantWithImageModel()
        fake.imageRefusal = "Das kann ich nicht darstellen."

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: "Ein Bild [[create_image]]"])

        then:
        result.answer.content.contains("(Error: the image model created no image - it answered: Das kann ich nicht darstellen.)")
        !result.answer.files
        imageUsage(setup.tenant).calls == 1
    }

    def "only images of this conversation, at most five: #reason"() {
        given:
        def setup = tenantWithImageModel()
        def other = api.chatUpload(setup.token, ["fremd.png": png(10, 10)])
        def uploaded = api.chatUpload(setup.token, ["notiz.txt": "Text".bytes] + (1..6).collectEntries { ["b${it}.png", png(10, it)] })
        def names = [document: "notiz.txt", otherConversation: "#" + other.files[0].id, unknown: "#999999",
                     six: (1..6).collect { "b${it}.png" }.join(" ")]

        when:
        def result = api.ask(setup.token, [conversationId: uploaded.conversation.id, modelId: setup.chat.id,
                                           message: "Bild [[create_image " + names[files] + "]]"])

        then:
        result.answer.content.contains(error)
        !result.answer.files
        fake.createdImages == []

        where:
        reason                       | files               | error
        "a document"                 | "document"          | "(Error: file_id"
        "another conversation's"     | "otherConversation" | "(Error: file_id"
        "an unknown file"            | "unknown"           | "(Error: file_id"
        "six images"                 | "six"               | "takes at most 5 images at once)"
    }

    def "without a Gemini key the chat's model learns why - the answer goes on, nothing is billed for the image"() {
        given: "a tenant chatting with Claude, its image model Gemini's - but only a key for Anthropic"
        def chat = api.chatModel("ANTHROPIC")
        def tenant = api.tenant()
        api.allow(tenant.id, chat, api.imageModel())
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-key-of-ANTHROPIC"])

        when:
        def result = api.ask(token, [modelId: chat.id, message: "Ein Bild [[create_image]]"])

        then:
        result.answer.content.contains("(Error: No API key for GEMINI")
        !result.answer.files
        fake.createdImages == []
        imageUsage(tenant) == null
    }

    def "a failing image model is an error the chat's model passes on - the answer goes on, nothing is billed"() {
        given:
        def setup = tenantWithImageModel()
        fake.imageFails = true

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: "Ein Bild [[create_image]]"])

        then: "the stream ends with the stored answer"
        result.answer.content.contains("(Error: GEMINI failed - please try again")
        !result.answer.files
        fake.createdImages == []
        imageUsage(setup.tenant) == null
    }

    def "an answer calls the image model at most three times - also when it refuses: #reason"() {
        given:
        def setup = tenantWithImageModel()
        fake.imageRefusal = refusal

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: "Bilder [[create_image_repeatedly]]"])

        then: "the fourth call is refused before the image model is asked"
        fake.createdImages*.instruction == ["Bild 1", "Bild 2", "Bild 3"]
        result.answer.content.endsWith("Error: at most 3 image model calls per answer")
        result.answer.files.size() == files

        and: "each of the three is billed"
        imageUsage(setup.tenant).calls == 3

        where:
        reason                   | refusal                   | files
        "it creates images"      | null                      | 3
        "it refuses every one"   | "Das kann ich nicht."     | 0
    }

    def "no tool without an image model the chat can use: #reason"() {
        given:
        def chat = api.chatModel("GEMINI")
        def tenant = api.tenant()
        def token = userWithKeys(tenant, [chat] + (0..<imageModels).collect { api.imageModel() })

        when:
        api.ask(token, [modelId: chat.id, message: "Ein Bild [[create_image]]"])

        then:
        !fake.chats.first().tools.contains("create_image")
        !(fake.chats.first().messages.first() as SystemMessage).text().contains("create_image")
        fake.createdImages == []

        where:
        reason                       | imageModels
        "none allowed"               | 0
        "two allowed, none chosen"   | 2
    }

    def "the client software creates images in a conversation it keeps - not without one"() {
        given:
        def setup = tenantWithImageModel()
        def keeping = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys", [name: "Software"]).plainKey
        def forgetting = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys",
                [name: "Ohne Verlauf", keepsHistory: false]).plainKey

        when:
        def uploaded = api.chatUpload(keeping, ["haus.png": png(20, 10)], null, "/api/v1/chat/files")
        def result = api.call(keeping, "POST", "/api/v1/chat", [conversationId: uploaded.conversation.id, modelId: setup.chat.id,
                                                                message: "Abends [[create_image haus.png]]"])

        then:
        result.answer.files*.fileName == ["haus-v2.png"]
        api.download(keeping, "/api/v1/chat/files/${result.answer.files[0].id}/content").status == HttpStatus.OK

        when:
        fake.reset()
        api.call(forgetting, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Ein Bild [[create_image]]"])

        then:
        !fake.chats.first().tools.contains("create_image")
        fake.createdImages == []
    }

    private Map tenantWithImageModel() {
        def chat = api.chatModel("GEMINI")
        def image = api.imageModel()
        def tenant = api.tenant()
        [tenant: tenant, chat: chat, image: image, token: userWithKeys(tenant, [chat, image])]
    }

    private String userWithKeys(Map tenant, List<Map> models) {
        api.allow(tenant.id, models as Map[])
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/GEMINI", [apiKey: "own-key-of-GEMINI"])
        admin
    }

    private Map imageUsage(Map tenant) {
        (api.admin("GET", "/admin/usage?tenantId=${tenant.id}") as List<Map>).find { it.kind == "IMAGE" }
    }

    private static byte[] png(int width, int height) {
        def out = new ByteArrayOutputStream()
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
        out.toByteArray()
    }

    /** Random pixels - a PNG that hardly compresses. */
    private static byte[] noise(int width, int height) {
        def image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        def random = new Random(1)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000))
            }
        }
        def out = new ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        out.toByteArray()
    }
}
