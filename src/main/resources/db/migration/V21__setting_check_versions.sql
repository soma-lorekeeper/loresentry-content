ALTER TABLE document_versions DROP CONSTRAINT ck_document_versions_kind;

ALTER TABLE document_versions
    ADD CONSTRAINT ck_document_versions_kind
        CHECK (kind IN ('AUTO', 'NAMED', 'AI_APPLY', 'RESTORE', 'REFRESH_BASE', 'SETTING_CHECK'));
