package de.alpdesk.services.tools;

import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The web search's call to Tavily through langchain4j's TavilyWebSearchEngine (docs/architecture/ai-features.md "Web
 * search") - the one class that knows Tavily: another search engine would replace it. The specs replace it with a fake.
 */
@Singleton
public class TavilySearch {

    /** The hits of one search, each with Tavily's extract of its page. */
    static final int HITS = 5;

    /** Tavily's address; null: the engine's own (https://api.tavily.com). */
    private final @Nullable String address;

    /**
     * @param address app.web-search.address - another address answering like Tavily (a test stack's fake); empty:
     *                Tavily's own
     */
    public TavilySearch(@Nullable @Value("${app.web-search.address:}") String address) {
        this.address = address == null || address.isBlank() ? null : address.strip();
    }

    /**
     * Searches with Tavily's basic depth (one credit). Each hit brings Tavily's extract - the part of the page that
     * matches the query, about 1,000 to 1,500 characters -, not the page's whole text: a page's text starts with its
     * menus and cookie notes, the fact sits further down (looked at 23 October 2026). Fails like langchain4j's HTTP
     * client - a refused key (401), too many requests (429), a plan's limit (432, 433), Tavily out of reach.
     */
    public List<WebSearchOrganicResult> search(String apiKey, String query) {
        TavilyWebSearchEngine engine = TavilyWebSearchEngine.builder().baseUrl(address).apiKey(apiKey)
                .searchDepth("basic").logRequests(false).logResponses(false).build();
        return engine.search(WebSearchRequest.from(query, HITS)).results();
    }
}
