package de.alpdesk.services.chat;

/** A text file sent with a question answered once (the client software without history, the MCP server): name and text. */
public record SentFile(String name, String content) {
}
