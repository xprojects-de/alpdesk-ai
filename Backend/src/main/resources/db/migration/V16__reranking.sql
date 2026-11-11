-- Reranking (docs/architecture/ai-features.md "Reranking"): a catalog entry of its own kind on an own server speaking the
-- /rerank API of Hugging Face Text Embeddings Inference (TEI) - the reranker's only provider. Like Ollama its address
-- and key are kept with the entry; priced per million tokens (estimated from the text, input_price; output_price unused).
ALTER TABLE ai_model DROP CONSTRAINT ai_model_kind_check;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_kind_check
    CHECK (kind IN ('CHAT', 'EMBEDDING', 'IMAGE', 'WEB_SEARCH', 'RERANK'));
ALTER TABLE ai_model DROP CONSTRAINT ai_model_provider_check;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_provider_check
    CHECK (provider IN ('ANTHROPIC', 'OPENAI', 'GEMINI', 'OLLAMA', 'OPENAI_COMPATIBLE', 'TAVILY', 'TEI'));
ALTER TABLE ai_model ADD CONSTRAINT ai_model_rerank_tei CHECK ((kind = 'RERANK') = (provider = 'TEI'));
ALTER TABLE ai_model DROP CONSTRAINT ai_model_server;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_server
    CHECK ((provider IN ('OLLAMA', 'OPENAI_COMPATIBLE', 'TEI')) = (base_url IS NOT NULL));

-- The reranker the tenant's knowledge search reorders its candidates with; null: off (the default). Switched off when
-- the model is deleted - the search then keeps the hybrid order.
ALTER TABLE tenant ADD COLUMN reranking_model_id BIGINT REFERENCES ai_model (id) ON DELETE SET NULL;
