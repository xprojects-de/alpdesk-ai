-- Web search in the chat (docs/architecture/ai-features.md "Web search"): a catalog entry of its own kind with Tavily as
-- its provider, assigned to a tenant like a model and priced per 1,000 searches (input_price; output_price unused).
-- Tavily's key is a provider key like the models' - the tenant's own before the admin's global one.
ALTER TABLE ai_model DROP CONSTRAINT ai_model_kind_check;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_kind_check CHECK (kind IN ('CHAT', 'EMBEDDING', 'IMAGE', 'WEB_SEARCH'));
ALTER TABLE ai_model DROP CONSTRAINT ai_model_provider_check;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_provider_check
    CHECK (provider IN ('ANTHROPIC', 'OPENAI', 'GEMINI', 'OLLAMA', 'OPENAI_COMPATIBLE', 'TAVILY'));
ALTER TABLE ai_model ADD CONSTRAINT ai_model_web_search_tavily CHECK ((kind = 'WEB_SEARCH') = (provider = 'TAVILY'));
ALTER TABLE provider_key DROP CONSTRAINT provider_key_provider_check;
ALTER TABLE provider_key ADD CONSTRAINT provider_key_provider_check
    CHECK (provider IN ('ANTHROPIC', 'OPENAI', 'GEMINI', 'TAVILY'));

-- Who may search the web: the members of a group with it, an API key with it, the MCP server with it - off by default.
ALTER TABLE user_group ADD COLUMN web_search BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tenant_api_key ADD COLUMN web_search BOOLEAN NOT NULL DEFAULT FALSE;

-- The MCP server's switch - kept per tenant, not per key, like its areas: a new MCP key keeps it. A row: switched on.
CREATE TABLE tenant_mcp_web_search
(
    tenant_id BIGINT PRIMARY KEY REFERENCES tenant (id) ON DELETE CASCADE
);
