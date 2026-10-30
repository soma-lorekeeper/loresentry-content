DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM refresh_document_drafts
               WHERE (left_snapshot IS NOT NULL AND left_snapshot_enc IS NULL)
                  OR (right_snapshot IS NOT NULL AND right_snapshot_enc IS NULL)
                  OR (origin_snapshot IS NOT NULL AND origin_snapshot_enc IS NULL)) THEN
        RAISE EXCEPTION 'refresh_document_drafts has snapshots without ciphertext';
    END IF;
END $$;

ALTER TABLE refresh_document_drafts DROP CONSTRAINT ck_refresh_document_drafts_kind;
ALTER TABLE refresh_document_drafts DROP CONSTRAINT ck_refresh_document_drafts_snapshots;

ALTER TABLE refresh_document_drafts
    DROP COLUMN matched,
    DROP COLUMN left_snapshot,
    DROP COLUMN right_snapshot,
    DROP COLUMN origin_snapshot;

ALTER TABLE refresh_document_drafts
    ADD CONSTRAINT ck_refresh_document_drafts_kind CHECK (
        (kind = 'EDIT' AND target_document_id IS NOT NULL AND candidate_id IS NULL AND folder_code IS NULL)
        OR (kind = 'NEW' AND target_document_id IS NULL AND base_version_id IS NULL
            AND candidate_id IS NOT NULL AND folder_code IS NOT NULL AND folder_code <> 'MANUSCRIPT'
            AND left_source_revision_no IS NULL AND origin_snapshot_enc IS NULL)),
    ADD CONSTRAINT ck_refresh_document_drafts_snapshots CHECK (
        kind = 'NEW'
        OR (left_source_revision_no IS NOT NULL
            AND left_snapshot_enc IS NOT NULL
            AND origin_snapshot_enc IS NOT NULL
            AND (status NOT IN ('OPEN', 'APPLIED', 'STALE') OR right_snapshot_enc IS NOT NULL)));

ALTER TABLE document
    DROP COLUMN body_json,
    DROP COLUMN body_text,
    DROP COLUMN body_md,
    DROP COLUMN body_sha;

ALTER TABLE document_versions DROP COLUMN snapshot;

ALTER TABLE refresh_results DROP COLUMN payload;
