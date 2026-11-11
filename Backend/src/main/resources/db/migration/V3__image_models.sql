-- Image models (docs/roadmap.md point 25): a third kind of model next to chat and embedding models, which creates images
-- in the chat - new ones, or from the images sent there. Only Gemini's for now - another provider needs its own call in
-- services.ai.AiModels first. The tenant's assistant settings name the one its chat uses.
ALTER TABLE ai_model DROP CONSTRAINT ai_model_kind_check;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_kind_check CHECK (kind IN ('CHAT', 'EMBEDDING', 'IMAGE'));
ALTER TABLE ai_model ADD CONSTRAINT ai_model_image_gemini CHECK (kind <> 'IMAGE' OR provider = 'GEMINI');

-- The image model the tenant's chat uses; NULL: the only one the tenant may use, or none if it may use several or none.
ALTER TABLE tenant_assistant ADD COLUMN image_model_id BIGINT REFERENCES ai_model (id) ON DELETE SET NULL;
