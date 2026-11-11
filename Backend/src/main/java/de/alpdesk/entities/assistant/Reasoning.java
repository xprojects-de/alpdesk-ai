package de.alpdesk.entities.assistant;

/** How much the model thinks before it answers - each provider's own parameter (services.ai.AiModels). */
public enum Reasoning {
    OFF(0),
    LOW(2048),
    MEDIUM(8192),
    HIGH(16384);

    /** The thinking tokens of the providers that take a budget (Anthropic, Gemini) - added to the output limit. */
    public final int budgetTokens;

    Reasoning(int budgetTokens) {
        this.budgetTokens = budgetTokens;
    }
}
