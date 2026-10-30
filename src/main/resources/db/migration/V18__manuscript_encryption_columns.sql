CREATE TABLE project_keys (
    project_id  UUID        NOT NULL,
    wrapped_key BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_project_keys PRIMARY KEY (project_id),
    CONSTRAINT fk_project_keys_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

ALTER TABLE document
    ADD COLUMN body_json_enc    BYTEA,
    ADD COLUMN body_md_enc      BYTEA,
    ADD COLUMN body_text_enc    BYTEA,
    ADD COLUMN body_text_mac    BYTEA,
    ADD COLUMN body_text_length INTEGER;

ALTER TABLE document_versions
    ADD COLUMN snapshot_enc BYTEA,
    ALTER COLUMN snapshot DROP NOT NULL;

ALTER TABLE refresh_document_drafts
    ADD COLUMN left_snapshot_enc   BYTEA,
    ADD COLUMN right_snapshot_enc  BYTEA,
    ADD COLUMN origin_snapshot_enc BYTEA,
    ADD COLUMN is_matched          BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE refresh_results
    ADD COLUMN payload_enc BYTEA,
    ALTER COLUMN payload DROP NOT NULL;
