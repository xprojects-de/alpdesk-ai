package de.alpdesk.services.chat;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.ChatOptions;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.common.ServiceException;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Writes the transcript of an uploaded audio file with the catalog's transcribing model (a chat model that reads
 * audio, chosen by the admin), so a model that cannot listen reads it as text (decided 6 October 2026). Without such a
 * model assigned to the tenant (its audio must not go to a provider it is not allowed), without a key for its provider
 * or when it fails, the file is kept without a transcript - the upload does not fail for it.
 */
@Singleton
public class AudioTranscriber {

    private static final Logger LOG = LoggerFactory.getLogger(AudioTranscriber.class);
    private static final String INSTRUCTIONS = """
            Transcribe the audio verbatim, in its own language. Mark changes of speaker with a new line. Answer with the
            transcript only.""";

    private final TenantModelService models;
    private final ProviderKeyService keys;
    private final AiModels aiModels;
    private final UsageService usage;

    public AudioTranscriber(TenantModelService models, ProviderKeyService keys, AiModels aiModels, UsageService usage) {
        this.models = models;
        this.keys = keys;
        this.aiModels = aiModels;
        this.usage = usage;
    }

    /** The transcript of the model's copy of an audio file (MP3), or empty. */
    public Optional<String> transcribe(Tenant tenant, Long userId, byte[] mp3) {
        Optional<AiModel> model = models.findTranscriber(tenant.id());
        if (model.isEmpty()) {
            return Optional.empty();
        }
        Optional<ResolvedKey> key = keys.find(tenant, model.get());
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            ChatResponse response = aiModels.chat(model.get(), key.get().apiKey(), List.of(
                    SystemMessage.from(INSTRUCTIONS),
                    UserMessage.from(TextContent.from("Transcribe this:"),
                            AudioContent.from(Base64.getEncoder().encodeToString(mp3), "audio/mpeg"))), List.of(),
                    ChatOptions.NONE);
            usage.record(tenant.id(), userId, model.get(), key.get().source(), response.tokenUsage(), 0);
            String text = response.aiMessage().text();
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.strip());
        } catch (ServiceException e) {
            LOG.warn("Audio of tenant {} could not be transcribed: {}", tenant.id(), e.getMessage());
            return Optional.empty();
        }
    }
}
