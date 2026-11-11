-- Knowledge sources (docs/architecture/knowledge-sources.md, roadmap point 8): a document may come from a folder of a
-- file store, kept up to date by its source (the management database's knowledge_source.id - no foreign key across
-- databases). "Behalten" when the source is removed turns its documents into uploads (source_id NULL).
ALTER TABLE document DROP CONSTRAINT document_origin_check;
ALTER TABLE document ADD CONSTRAINT document_origin_check CHECK (origin IN ('UPLOAD', 'API', 'SOURCE'));
ALTER TABLE document ADD COLUMN source_id BIGINT;
CREATE INDEX document_source_idx ON document (source_id);

-- What a source saw, file by file: a file of the same size and time is not read again, a refused one is remembered
-- (a large video is not read again only to be refused once more), and a vanished one tells which document to delete.
-- document_id NULL with an error: refused; NULL without one: its document was deleted by hand - left alone until the
-- file changes.
CREATE TABLE source_file
(
    source_id   BIGINT NOT NULL,
    -- Relative to the source's folder ("Projekte/2025/Angebot.pdf").
    path        TEXT   NOT NULL,
    size        BIGINT NOT NULL,
    modified    TIMESTAMPTZ,
    document_id BIGINT REFERENCES document (id) ON DELETE SET NULL,
    error       TEXT,
    PRIMARY KEY (source_id, path)
);

CREATE INDEX source_file_document_idx ON source_file (document_id);
