package de.alpdesk.entities.ai;

/** The AI providers a model can come from (services.ai.AiModels builds their langchain4j models). */
public enum AiProvider {
    ANTHROPIC,
    OPENAI,
    GEMINI,
    /** An own Ollama server, through Ollama's own API (it can be told the context length per request). */
    OLLAMA,
    /** Any server speaking the OpenAI API: a company's model farm, vLLM, Mistral, Azure OpenAI's /openai/v1, ... */
    OPENAI_COMPATIBLE,
    /**
     * Amazon Bedrock's models through its Converse API - Claude in an EU region with the installation's own AWS account:
     * the region's address and an IAM access key at the catalog entry like an own server's.
     */
    BEDROCK,
    /** The chat's web search - no models, only the catalog entry of kind WEB_SEARCH and its key. */
    TAVILY,
    /**
     * A server speaking the /rerank API of Hugging Face Text Embeddings Inference - the reranker's only provider, its
     * address and key at the catalog entry like an Ollama's.
     */
    TEI;

    /**
     * Whether its models run on a server the admin enters with the model (address and key at the catalog entry) - the
     * others have one fixed address and their keys under API-Keys.
     */
    public boolean hasOwnServer() {
        return this == OLLAMA || this == OPENAI_COMPATIBLE || this == BEDROCK || this == TEI;
    }

    /**
     * Whether a history with tool calls is refused without the tools' definitions - Claude's rule, at Anthropic and on
     * Bedrock ("must define tools", "toolConfig field must be defined"). The others on own servers differ.
     */
    public boolean needsToolsWithToolCalls() {
        return !hasOwnServer() || this == BEDROCK;
    }
}
