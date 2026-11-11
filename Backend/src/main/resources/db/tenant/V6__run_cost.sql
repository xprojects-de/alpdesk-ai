-- A run keeps what its steps cost (docs/architecture/tasks.md "Data", roadmap point 15): with price tiers and cached
-- tokens a step's cost cannot be computed from its summed tokens afterwards. Runs and steps from before keep NULL and
-- are computed at the catalog's price as before; new ones start at 0.
ALTER TABLE task_run_step ADD COLUMN cache_read_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_run_step ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_run_step ADD COLUMN cost NUMERIC(14, 6);

ALTER TABLE task_run ADD COLUMN cache_read_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_run ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_run ADD COLUMN cost NUMERIC(14, 6);
ALTER TABLE task_run ALTER COLUMN cost SET DEFAULT 0;
