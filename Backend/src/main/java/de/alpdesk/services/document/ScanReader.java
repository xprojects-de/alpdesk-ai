package de.alpdesk.services.document;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.CallTokens;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.Spent;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.common.ServiceException;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Reads what Tika cannot (docs/architecture/ai-features.md "Images and scans"): an image, and the pages of a PDF with
 * (almost) no text - a scan, handwritten notes, a slide that is mostly a drawing. The catalog's scan reader (a chat
 * model, all of them see images) transcribes each such page and describes what it shows; its text replaces the page's
 * before the document is embedded. One model call per image or page, recorded as usage.
 */
@Singleton
public class ScanReader {

    private static final Logger LOG = LoggerFactory.getLogger(ScanReader.class);
    /** Below this many characters a PDF page counts as an image: a slide's title alone says nothing. */
    static final int MIN_PAGE_TEXT = 200;
    /** The pages of one document read by the model at most - beyond, only their text counts. */
    static final int MAX_READ_PAGES = 200;
    /** Opens each page of a PDF in the text read: "--- Page 3 ---". */
    static final String PAGE_MARKER = "--- Page ";
    private static final String INSTRUCTIONS = """
            You turn an image from a company's documents into text for its knowledge base - a scanned page, handwritten
            notes, a slide, a photo of a whiteboard or of a situation.
            1. Transcribe all text exactly, in its language - keep headings, lists and tables (as Markdown).
            2. Then describe every drawing, sketch, diagram and photo in detail: what it shows, the positions,
               directions, movements, distances and labels - so that someone who cannot see it understands it.
            Answer in the language of the image's text (German if it has none). No preface, no comments of your own.""";

    private final AiModels aiModels;
    private final UsageService usage;

    public ScanReader(AiModels aiModels, UsageService usage) {
        this.aiModels = aiModels;
        this.usage = usage;
    }

    /** Whether the model must read this document: an image, or a PDF with a page of (almost) no text. */
    public static boolean needsReading(String contentType, byte[] content) {
        if (DocumentImages.IMAGES.contains(contentType)) {
            return true;
        }
        if (!contentType.equals("application/pdf")) {
            return false;
        }
        return DocumentImages.pageTexts(content).stream().anyMatch(page -> page.length() < MIN_PAGE_TEXT);
    }

    /** The document's text with its images read: the image's reading, or the PDF page by page. */
    public String read(Tenant tenant, AiModel model, ResolvedKey key, String contentType, String fileName, byte[] content) {
        return read(tenant, model, key, contentType, fileName, content, new Spent());
    }

    /** The same, each call counted into what a task's step spent - the run's cost. */
    public String read(Tenant tenant, AiModel model, ResolvedKey key, String contentType, String fileName, byte[] content,
                       Spent spent) {
        if (DocumentImages.IMAGES.contains(contentType)) {
            return describe(tenant, model, key, "The image \"" + fileName + "\"", DocumentImages.imageAsJpeg(content),
                    spent);
        }
        List<String> pages = DocumentImages.pageTexts(content);
        StringBuilder text = new StringBuilder();
        int read = 0;
        for (int page = 1; page <= pages.size(); page++) {
            String pageText = pages.get(page - 1);
            if (pageText.length() < MIN_PAGE_TEXT && read < MAX_READ_PAGES) {
                Optional<byte[]> image = renderedPage(tenant, content, page);
                if (image.isPresent()) {
                    pageText = describe(tenant, model, key, "Page " + page + " of \"" + fileName + "\"", image.get(),
                            spent);
                    read++;
                }
            }
            text.append(PAGE_MARKER).append(page).append(" ---\n").append(pageText).append("\n\n");
        }
        return text.toString().strip();
    }

    /**
     * The page as an image - empty when it cannot be rendered (an image on it larger than DocumentImages allows, a
     * broken drawing). Such a page keeps the text it has: one page must not fail the whole document, whose pages read
     * before it would be paid again on every retry and fail again.
     */
    private static Optional<byte[]> renderedPage(Tenant tenant, byte[] pdf, int page) {
        try {
            return Optional.of(DocumentImages.renderPages(pdf, page, page).images().getFirst());
        } catch (ServiceException e) {
            LOG.warn("Page {} of a document of tenant {} is not read: {}", page, tenant.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private String describe(Tenant tenant, AiModel model, ResolvedKey key, String label, byte[] jpeg, Spent spent) {
        ChatResponse response = aiModels.chatInBackground(model, key.apiKey(), List.of(SystemMessage.from(INSTRUCTIONS),
                UserMessage.from(TextContent.from(label + ":"),
                        ImageContent.from(Base64.getEncoder().encodeToString(jpeg), "image/jpeg"))));
        BigDecimal cost = usage.record(tenant.id(), null, model, key.source(), response.tokenUsage(), 0);
        spent.add(CallTokens.of(response.tokenUsage()), cost);
        String text = response.aiMessage().text();
        return text == null ? "" : text.strip();
    }
}
