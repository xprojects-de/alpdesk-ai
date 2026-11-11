package de.alpdesk.services.ai;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * langchain4j's scoring model for a server speaking the /rerank API of Hugging Face Text Embeddings Inference (TEI) -
 * langchain4j has no module for it. One request scores every segment against the query; the server answers the scores
 * sorted by score, each with the segment's index, and this puts them back in the segments' order.
 */
class TeiScoringModel implements ScoringModel {

    private static final JsonMapper JSON = JsonMapper.createDefault();

    private final HttpClient client;
    private final String baseUrl;
    private final @Nullable String apiKey;

    TeiScoringModel(HttpClient client, String baseUrl, @Nullable String apiKey) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        List<String> texts = segments.stream().map(TextSegment::text).toList();
        HttpRequest.Builder request = HttpRequest.builder().method(HttpMethod.POST).url(baseUrl, "/rerank")
                .addHeader("Content-Type", "application/json")
                // A chunk longer than the model reads is cut by the server, not refused.
                .body(write(new RerankRequest(query, texts, true)));
        if (apiKey != null) {
            request.addHeader("Authorization", "Bearer " + apiKey);
        }
        return Response.from(inSegmentOrder(read(execute(request.build())), segments.size()));
    }

    private String execute(HttpRequest request) {
        try {
            return client.execute(request).body();
        } catch (HttpException e) {
            // The same exceptions as langchain4j's provider modules: AiModels names the failure from them.
            throw switch (e.statusCode()) {
                case 401, 403 -> new AuthenticationException("The reranker refused the key", e);
                case 429 -> new RateLimitException(e);
                default -> e;
            };
        }
    }

    private static List<Double> inSegmentOrder(RankedText[] ranked, int count) {
        if (ranked.length != count) {
            throw new IllegalStateException("The reranker scored " + ranked.length + " of " + count + " texts");
        }
        Double[] scores = new Double[count];
        for (RankedText text : ranked) {
            if (text.index() < 0 || text.index() >= count || scores[text.index()] != null) {
                throw new IllegalStateException("The reranker answered an unknown index " + text.index());
            }
            scores[text.index()] = text.score();
        }
        return new ArrayList<>(Arrays.asList(scores));
    }

    private static String write(RerankRequest request) {
        try {
            return JSON.writeValueAsString(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RankedText[] read(String body) {
        try {
            return JSON.readValue(body, RankedText[].class);
        } catch (IOException e) {
            throw new IllegalStateException("The reranker's answer is not readable", e);
        }
    }

    @Serdeable
    record RerankRequest(String query, List<String> texts, boolean truncate) {
    }

    @Serdeable
    record RankedText(int index, double score) {
    }
}
