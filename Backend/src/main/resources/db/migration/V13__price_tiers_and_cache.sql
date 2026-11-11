-- Price tiers and cache prices (docs/architecture/ai-features.md "Prices", roadmap point 15): a model may bill a
-- request whose prompt is longer than a threshold at higher prices (Claude Haiku 5.5 above 100 000 tokens), and a
-- cached token at its own price. All optional, per million tokens like the prices: without a threshold one price, as
-- before; without cache prices a cached token costs an input token.
ALTER TABLE ai_model ADD COLUMN tier_threshold INTEGER CHECK (tier_threshold > 0);
ALTER TABLE ai_model ADD COLUMN tier_input_price NUMERIC(12, 4) CHECK (tier_input_price >= 0);
ALTER TABLE ai_model ADD COLUMN tier_output_price NUMERIC(12, 4) CHECK (tier_output_price >= 0);
ALTER TABLE ai_model ADD COLUMN cache_read_price NUMERIC(12, 4) CHECK (cache_read_price >= 0);
ALTER TABLE ai_model ADD COLUMN cache_write_price NUMERIC(12, 4) CHECK (cache_write_price >= 0);
ALTER TABLE ai_model ADD COLUMN tier_cache_read_price NUMERIC(12, 4) CHECK (tier_cache_read_price >= 0);
ALTER TABLE ai_model ADD COLUMN tier_cache_write_price NUMERIC(12, 4) CHECK (tier_cache_write_price >= 0);

-- A call's cached tokens, apart from its input: read from the provider's cache, and written to it. input_tokens is
-- what was billed at the input price - the three together are the prompt's length.
ALTER TABLE usage_record ADD COLUMN cache_read_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE usage_record ADD COLUMN cache_write_tokens INTEGER NOT NULL DEFAULT 0;
