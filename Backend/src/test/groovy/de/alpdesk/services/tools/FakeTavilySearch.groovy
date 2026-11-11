package de.alpdesk.services.tools

import dev.langchain4j.exception.HttpException
import dev.langchain4j.web.search.WebSearchOrganicResult
import io.micronaut.context.annotation.Replaces

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for Tavily in the specs: every search is recorded with its key, and finds two pages on example.com, each
 * with Tavily's extract (the first one longer than the tool keeps with longPages).
 */
@jakarta.inject.Singleton
@Replaces(TavilySearch)
class FakeTavilySearch extends TavilySearch {

    /** Every search: [apiKey: ..., query: ...]. */
    final List<Map> searches = new CopyOnWriteArrayList<>()
    /** The next searches fail like Tavily refusing the key. */
    volatile int failures = 0
    /** The searches find nothing. */
    volatile boolean nothingFound = false
    /** The first page's extract is longer than the tool keeps of it. */
    volatile boolean longPages = false

    FakeTavilySearch() {
        super(null)
    }

    void reset() {
        searches.clear()
        failures = 0
        nothingFound = false
        longPages = false
    }

    @Override
    List<WebSearchOrganicResult> search(String apiKey, String query) {
        searches << [apiKey: apiKey, query: query]
        if (failures > 0) {
            failures--
            // As langchain4j's HTTP client throws it: Tavily's answer as the message - here repeating key and query.
            throw new HttpException(401, "{\"detail\":{\"error\":\"Unauthorized: ${apiKey} for ${query}\"}}")
        }
        if (nothingFound) {
            return []
        }
        String text = longPages ? "Seitentext " + "x" * 5000 : "Seitentext zu " + query
        [WebSearchOrganicResult.from("Seite zu " + query, URI.create("https://example.com/seite"), text, null),
         WebSearchOrganicResult.from("Zweite Seite", URI.create("https://example.org/zweite"), "Nur der Ausschnitt", null)]
    }
}
