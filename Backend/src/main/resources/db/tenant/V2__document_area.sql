-- Knowledge areas (docs/architecture/ai-features.md "Knowledge areas"): the area a document belongs to (the management
-- database's knowledge_area.id - no foreign key across databases); NULL = the company knowledge everyone finds.
ALTER TABLE document ADD COLUMN area_id BIGINT;
CREATE INDEX document_area_idx ON document (area_id);

-- The path of a ZIP archive's file is its key within its area: the same folder tree imported into two areas is two sets
-- of documents. The client software's own key (and later a source's) stays unique per origin - a push with another area
-- moves its document.
DROP INDEX document_external_id_uq;
CREATE UNIQUE INDEX document_upload_path_uq ON document (COALESCE(area_id, 0), external_id)
    WHERE origin = 'UPLOAD' AND external_id IS NOT NULL;
CREATE UNIQUE INDEX document_external_id_uq ON document (origin, external_id)
    WHERE origin <> 'UPLOAD' AND external_id IS NOT NULL;

-- The client software's conversations belong to the API key that started them (docs/architecture/ai-features.md
-- "Chat"): each key finds its own knowledge areas, so another key must not read its answers. The management
-- database's tenant_api_key.id - no foreign key across databases; revoking a key deletes its conversations.
-- Conversations of the client software from before have none and are reached by no key.
ALTER TABLE conversation ADD COLUMN api_key_id BIGINT;
CREATE INDEX conversation_api_key_idx ON conversation (api_key_id, updated_at);
