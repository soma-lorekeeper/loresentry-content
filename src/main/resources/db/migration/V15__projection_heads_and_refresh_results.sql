-- Latest required event per document survives Outbox cleanup. No document FK: a
-- deleted document's last event remains a prerequisite until project deletion.
CREATE TABLE projection_heads (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    file_id UUID NOT NULL,
    event_id UUID NOT NULL,
    PRIMARY KEY (project_id, file_id)
);
ALTER TABLE refresh_runs ADD COLUMN input_fingerprint VARCHAR(64);
ALTER TABLE refresh_runs ADD COLUMN result_bucket TEXT;
ALTER TABLE refresh_runs ADD COLUMN result_key TEXT;
CREATE TABLE refresh_results (
    refresh_run_id UUID PRIMARY KEY REFERENCES refresh_runs(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
