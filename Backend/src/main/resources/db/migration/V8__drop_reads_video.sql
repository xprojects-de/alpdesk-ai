-- A video is examined with code in the tenant's sandbox, no longer read by a model as it is (docs/roadmap.md point 35):
-- the catalog's "reads video" goes.
ALTER TABLE ai_model DROP COLUMN reads_video;
