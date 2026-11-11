-- A chat file is deleted from the storage after its retention time (app.chat.video-retention-hours for an uploaded
-- video, app.chat.file-retention-days for every other - docs/roadmap.md point 35); its row stays without its text,
-- marked with when, so the conversation and the model see that it expired.
ALTER TABLE conversation_file ADD COLUMN expired_at TIMESTAMPTZ;
