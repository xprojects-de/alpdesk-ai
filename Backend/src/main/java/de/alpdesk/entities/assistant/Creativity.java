package de.alpdesk.entities.assistant;

/** How freely the model writes - sent as its temperature (services.ai.AiModels). */
public enum Creativity {
    LOW(0.2),
    MEDIUM(0.7),
    HIGH(1.0);

    /** Within every provider's range (Anthropic takes 0..1, OpenAI and Gemini 0..2). */
    public final double temperature;

    Creativity(double temperature) {
        this.temperature = temperature;
    }
}
