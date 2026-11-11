package de.alpdesk.entities.tenant;

import java.util.Locale;

/**
 * The language of a tenant's documents as the full-text search reads them: PostgreSQL's built-in text search
 * configuration of that name - its stemming ("Wartungen" finds "Wartung") and stop words. SIMPLE is no language: words
 * are found as they are written, which is never wrong, only weaker. The vector search does not depend on it.
 * <p>
 * Exactly the configurations every PostgreSQL 18 brings (pg_ts_config); the name goes into SQL, so only these.
 */
public enum SearchLanguage {
    SIMPLE,
    ARABIC,
    ARMENIAN,
    BASQUE,
    CATALAN,
    DANISH,
    DUTCH,
    ENGLISH,
    ESTONIAN,
    FINNISH,
    FRENCH,
    GERMAN,
    GREEK,
    HINDI,
    HUNGARIAN,
    INDONESIAN,
    IRISH,
    ITALIAN,
    LITHUANIAN,
    NEPALI,
    NORWEGIAN,
    PORTUGUESE,
    ROMANIAN,
    RUSSIAN,
    SERBIAN,
    SPANISH,
    SWEDISH,
    TAMIL,
    TURKISH,
    YIDDISH;

    /** The name of PostgreSQL's text search configuration. */
    public String config() {
        return name().toLowerCase(Locale.ROOT);
    }
}
