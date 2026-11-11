package de.alpdesk.services.tools;

import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * What a tool answers: text for the model and, optionally, files for it to look at (images, an original PDF). A tool
 * result itself can only be text with every provider, so the files follow the results as a message of their own (see
 * services.agent.AgentLoop).
 */
public record ToolResult(String text, List<ToolAttachment> attachments) {

    public static ToolResult text(String text) {
        return new ToolResult(text, List.of());
    }

    public static ToolResult error(String message) {
        return text("Error: " + message);
    }

    /** The files for the model's message: each with what it is, then the file itself. */
    public List<Content> attachmentContents() {
        List<Content> contents = new ArrayList<>();
        for (ToolAttachment attachment : attachments) {
            contents.add(TextContent.from(attachment.label() + ":"));
            contents.add(attachment.asContent());
        }
        return contents;
    }

    /**
     * A file for the model - image/jpeg, application/pdf or audio/mpeg - and what it is (e.g. "page 2 of
     * scan.pdf").
     */
    public record ToolAttachment(String label, String mimeType, byte[] content) {

        public static ToolAttachment jpeg(String label, byte[] content) {
            return new ToolAttachment(label, "image/jpeg", content);
        }

        private Content asContent() {
            String base64 = Base64.getEncoder().encodeToString(content);
            if (mimeType.equals("application/pdf")) {
                return PdfFileContent.from(base64, mimeType);
            }
            if (mimeType.startsWith("audio/")) {
                return AudioContent.from(base64, mimeType);
            }
            return ImageContent.from(base64, mimeType);
        }
    }
}
