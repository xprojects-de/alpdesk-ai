package de.alpdesk.entities.assistant;

/** What the assistant answers from - a rule of its system prompt (services.chat.ChatService). */
public enum AnswerSource {
    /** Only from the company's documents; if they do not answer, it says so. */
    DOCUMENTS_ONLY,
    /** The documents first; general knowledge where they do not cover the question, marked as such. */
    DOCUMENTS_FIRST
}
