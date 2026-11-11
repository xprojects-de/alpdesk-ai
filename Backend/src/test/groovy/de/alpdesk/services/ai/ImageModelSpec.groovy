package de.alpdesk.services.ai

import de.alpdesk.entities.ai.AiModel
import de.alpdesk.entities.ai.AiProvider
import de.alpdesk.entities.ai.ModelKind
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.TokenUsage
import spock.lang.Specification

import java.time.Duration
import java.time.Instant

/**
 * What AiModels sends to an image model and reads from its answer (docs/roadmap.md point 25), checked on langchain4j's
 * types; the HTTP request and answer themselves run against a fake Gemini server in ModelServerSpec.
 */
class ImageModelSpec extends Specification {

    def "the instruction goes first, then the images in their order"() {
        when:
        def message = AiModels.imageRequest("Setze das Haus in die Landschaft", [image("haus", "image/png"), image("wiese", "image/jpeg")])

        then:
        message.contents().size() == 3
        (message.contents()[0] as TextContent).text() == "Setze das Haus in die Landschaft"
        (message.contents()[1] as ImageContent).image().base64Data() == base64("haus")
        (message.contents()[2] as ImageContent).image().mimeType() == "image/jpeg"
    }

    def "a new image needs no image sent"() {
        expect:
        AiModels.imageRequest("Eine Einladung zum Geburtstag", []).contents()*.class == [TextContent]
    }

    def "the image of the answer is read with its type, its text and the usage"() {
        given:
        def usage = new TokenUsage(300, 1290)

        when:
        def created = AiModels.createdImage(response(" Hier ist das Bild. ", [image("bild", "image/png")], usage))

        then:
        created.hasImage()
        new String(created.content()) == "bild"
        created.mimeType() == "image/png"
        created.text() == "Hier ist das Bild."
        created.usage() == usage
    }

    def "an image without a type is taken for a PNG; of several the first counts"() {
        when:
        def created = AiModels.createdImage(response(null, [image("erstes", null), image("zweites", "image/jpeg")], null))

        then:
        new String(created.content()) == "erstes"
        created.mimeType() == "image/png"
        created.text() == ""
        created.usage() == null
    }

    def "an answer without an image keeps its text - the model's reason - and its usage, which is billed all the same"() {
        when:
        def created = AiModels.createdImage(response("Das kann ich nicht erstellen.", [], new TokenUsage(40, 12)))

        then:
        !created.hasImage()
        created.content() == null
        created.text() == "Das kann ich nicht erstellen."
        created.usage().outputTokenCount() == 12
    }

    def "only Gemini's image models take images: #provider takes #count"() {
        expect:
        new AiModels(Duration.ofSeconds(1), 100, new ModelServers(""), new BedrockClients(Duration.ofSeconds(1)))
                .imagesPerCall(model(provider)) == count

        where:
        provider                     | count
        AiProvider.GEMINI            | 5
        AiProvider.OPENAI            | 0
        AiProvider.ANTHROPIC         | 0
        AiProvider.OLLAMA            | 0
        AiProvider.OPENAI_COMPATIBLE | 0
        AiProvider.BEDROCK           | 0
    }

    def "the specs' fake image carries what reached the image model"() {
        expect:
        FakeAiModels.description(FakeAiModels.png("Hintergrund weiß | images: 2")) == "Hintergrund weiß | images: 2"
    }

    private static ChatResponse response(String text, List<Image> images, TokenUsage usage) {
        def message = AiMessage.builder().text(text)
                .attributes(images ? [(AiMessage.GENERATED_IMAGES_KEY): images] : [:]).build()
        ChatResponse.builder().aiMessage(message).tokenUsage(usage).build()
    }

    private static Image image(String content, String mimeType) {
        Image.builder().base64Data(base64(content)).mimeType(mimeType).build()
    }

    private static String base64(String content) {
        Base64.encoder.encodeToString(content.bytes)
    }

    private static AiModel model(AiProvider provider) {
        new AiModel(1L, provider, ModelKind.IMAGE, "image-model", "Bild", null, BigDecimal.ONE, BigDecimal.ONE, true,
                false, false, false, false, true, false, true, null, null, null, null, null, null, null, null, null, null, null, null, Instant.now(),
                Instant.now())
    }
}
