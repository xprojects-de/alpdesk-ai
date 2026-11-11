-- The language the full-text search reads a tenant's documents in (docs/architecture/ai-features.md "Search"):
-- PostgreSQL's text search configuration of that name, entities.tenant.SearchLanguage. A tenant's index is built for
-- it; changing it builds the index again, nothing is embedded again. Tenants from before were searched in German and
-- keep it; a new tenant gets what the admin chooses, SIMPLE (no language) when nothing is chosen - set by the
-- application, so the column has no default.
ALTER TABLE tenant
    ADD COLUMN search_language TEXT NOT NULL DEFAULT 'GERMAN' CHECK (search_language IN (
        'SIMPLE', 'ARABIC', 'ARMENIAN', 'BASQUE', 'CATALAN', 'DANISH', 'DUTCH', 'ENGLISH', 'ESTONIAN', 'FINNISH',
        'FRENCH', 'GERMAN', 'GREEK', 'HINDI', 'HUNGARIAN', 'INDONESIAN', 'IRISH', 'ITALIAN', 'LITHUANIAN', 'NEPALI',
        'NORWEGIAN', 'PORTUGUESE', 'ROMANIAN', 'RUSSIAN', 'SERBIAN', 'SPANISH', 'SWEDISH', 'TAMIL', 'TURKISH',
        'YIDDISH'));
ALTER TABLE tenant
    ALTER COLUMN search_language DROP DEFAULT;
