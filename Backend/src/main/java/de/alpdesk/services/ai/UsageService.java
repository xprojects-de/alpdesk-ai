package de.alpdesk.services.ai;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.KeySource;
import de.alpdesk.entities.ai.UsageRecord;
import de.alpdesk.entities.ai.UsageSummary;
import de.alpdesk.repositories.ai.UsageRecordRepository;
import dev.langchain4j.model.output.TokenUsage;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/** Records every model call with what it costs, and sums it up per month (docs/architecture/ai-features.md). */
@Singleton
public class UsageService {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    /** A rough rule for the providers' tokenizers: about four characters per token. */
    private static final int CHARACTERS_PER_TOKEN = 4;

    private final UsageRecordRepository repository;
    private final ZoneId zone;

    public UsageService(UsageRecordRepository repository, @Value("${app.timezone}") String zone) {
        this.repository = repository;
        this.zone = ZoneId.of(zone);
    }

    /**
     * Records a call at the model's current prices and returns what it cost - its cached tokens apart (CallTokens).
     * Without a token count from the provider (some embedding APIs) the input tokens are estimated from
     * {@code inputCharacters}.
     */
    @Transactional
    public BigDecimal record(Long tenantId, Long userId, AiModel model, KeySource keySource, TokenUsage usage,
                             long inputCharacters) {
        CallTokens reported = CallTokens.of(usage);
        boolean estimated = reported.input() == null;
        CallTokens tokens = estimated ? new CallTokens((int) Math.ceil(inputCharacters / (double) CHARACTERS_PER_TOKEN),
                reported.output(), reported.cacheRead(), reported.cacheWrite()) : reported;
        BigDecimal amount = amount(model, tokens);
        repository.save(new UsageRecord(null, tenantId, userId, model.provider(), model.modelName(), model.kind(),
                keySource, tokens.input(), tokens.output(), tokens.cacheRead(), tokens.cacheWrite(), estimated, amount,
                Instant.now()));
        return amount;
    }

    /**
     * What a call costs at the model's current prices - the one formula, so a task's run rounds like the usage. A
     * prompt longer than the model's threshold puts the whole call on the tier prices (as Anthropic and Gemini bill
     * it); a cache price not set is the input price of the call's tier.
     */
    public static BigDecimal amount(AiModel model, CallTokens tokens) {
        return amount(model, tokens, model.tierThreshold() != null && tokens.promptLength() > model.tierThreshold());
    }

    /**
     * The summed tokens of several calls - a task's step from before its cost was kept: which call was above the
     * threshold is lost, so they are priced below it, at least what they cost.
     */
    public static BigDecimal amountOfSeveralCalls(AiModel model, long inputTokens, long outputTokens) {
        return amount(model, CallTokens.plain(inputTokens, outputTokens), false);
    }

    private static BigDecimal amount(AiModel model, CallTokens tokens, boolean tier) {
        BigDecimal input = tier ? model.tierInputPrice() : model.inputPrice();
        BigDecimal output = tier ? model.tierOutputPrice() : model.outputPrice();
        BigDecimal read = orElse(tier ? model.tierCacheReadPrice() : model.cacheReadPrice(), input);
        BigDecimal write = orElse(tier ? model.tierCacheWritePrice() : model.cacheWritePrice(), input);
        return input.multiply(BigDecimal.valueOf(tokens.input() == null ? 0 : tokens.input()))
                .add(output.multiply(BigDecimal.valueOf(tokens.output())))
                .add(read.multiply(BigDecimal.valueOf(tokens.cacheRead())))
                .add(write.multiply(BigDecimal.valueOf(tokens.cacheWrite())))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal orElse(@Nullable BigDecimal price, BigDecimal fallback) {
        return price != null ? price : fallback;
    }

    /**
     * Records web searches at the catalog entry's price per 1,000 searches - the searches are kept as its input
     * tokens, so the usage pages sum them like the models' tokens.
     */
    @Transactional
    public void recordSearches(Long tenantId, @Nullable Long userId, AiModel webSearch, KeySource keySource, int searches) {
        BigDecimal amount = webSearch.inputPrice().multiply(BigDecimal.valueOf(searches))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        repository.save(new UsageRecord(null, tenantId, userId, webSearch.provider(), webSearch.modelName(),
                webSearch.kind(), keySource, searches, 0, 0, 0, false, amount, Instant.now()));
    }

    /** The usage of the month (in app.timezone) - of one tenant, or of all when tenantId is null. */
    public List<UsageSummary> summarize(Long tenantId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return repository.summarize(tenantId, from, to);
    }
}
