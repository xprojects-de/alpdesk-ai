package de.alpdesk.entities.ai;

public enum ModelKind {
    /** Answers in the chat. */
    CHAT,
    /** Turns document chunks and questions into vectors (pgvector). */
    EMBEDDING,
    /**
     * Creates images in the chat - new ones, or from the images sent there - called by the chat's model, never chatting
     * itself (it calls no tools). Gemini only for now (docs/roadmap.md point 25).
     */
    IMAGE,
    /**
     * The chat's web search (Tavily, docs/architecture/ai-features.md "Web search"), called by the chat's model - no
     * model at all: its input price is per 1,000 searches, its output price unused.
     */
    WEB_SEARCH,
    /**
     * Reorders the knowledge search's candidates by how well each answers the question - a cross-encoder on an own
     * server (TEI, docs/architecture/ai-features.md "Reranking"); its input price is per million tokens, estimated.
     */
    RERANK
}
