CREATE TABLE projects (
    id            UUID         NOT NULL DEFAULT uuidv7(),
    owner_user_id UUID         NOT NULL,
    name          VARCHAR(200) NOT NULL,
    description   TEXT,
    trashed_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_projects PRIMARY KEY (id)
);

CREATE INDEX ix_projects_owner_user_id ON projects (owner_user_id);

CREATE TABLE base_folders (
    id       SMALLINT    NOT NULL,
    code     VARCHAR(40) NOT NULL,
    name     VARCHAR(50) NOT NULL,
    position INTEGER     NOT NULL,
    CONSTRAINT pk_base_folders PRIMARY KEY (id),
    CONSTRAINT uq_base_folders_code UNIQUE (code)
);

CREATE TABLE episode_folders (
    id                 UUID                     NOT NULL DEFAULT uuidv7(),
    project_id         UUID                     NOT NULL,
    created_by_user_id UUID                     NOT NULL,
    name               VARCHAR(200)             NOT NULL,
    rank               VARCHAR(255) COLLATE "C" NOT NULL,
    created_at         TIMESTAMPTZ              NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ              NOT NULL DEFAULT now(),
    CONSTRAINT pk_episode_folders PRIMARY KEY (id),
    CONSTRAINT uq_episode_folders_id_project UNIQUE (id, project_id),
    CONSTRAINT fk_episode_folders_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX ix_episode_folders_project_rank ON episode_folders (project_id, rank);

CREATE TABLE document (
    id          UUID                     NOT NULL DEFAULT uuidv7(),
    project_id  UUID                     NOT NULL,
    folder_id   SMALLINT                 NOT NULL,
    episode_id  UUID,
    title       VARCHAR(255)             NOT NULL,
    body_md     TEXT                     NOT NULL DEFAULT '',
    body_sha    VARCHAR(80),
    char_count  INTEGER                  NOT NULL DEFAULT 0,
    rank        VARCHAR(255) COLLATE "C" NOT NULL,
    revision_no BIGINT                   NOT NULL DEFAULT 0,
    locked      BOOLEAN                  NOT NULL DEFAULT false,
    trashed_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ              NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ              NOT NULL DEFAULT now(),
    CONSTRAINT pk_document PRIMARY KEY (id),
    CONSTRAINT fk_document_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_document_folder FOREIGN KEY (folder_id) REFERENCES base_folders (id),
    CONSTRAINT fk_document_episode FOREIGN KEY (episode_id, project_id)
        REFERENCES episode_folders (id, project_id),
    CONSTRAINT ck_document_episode_in_manuscript CHECK (episode_id IS NULL OR folder_id = 4),
    CONSTRAINT ck_document_char_count CHECK (char_count >= 0),
    CONSTRAINT ck_document_revision_no CHECK (revision_no >= 0)
);

CREATE INDEX ix_document_project_folder_rank ON document (project_id, folder_id, rank);
CREATE INDEX ix_document_episode_rank ON document (episode_id, rank) WHERE episode_id IS NOT NULL;

CREATE TABLE document_properties (
    id           UUID         NOT NULL DEFAULT uuidv7(),
    document_id  UUID         NOT NULL,
    property_key VARCHAR(100) NOT NULL,
    text_value   TEXT         NOT NULL,
    position     INTEGER      NOT NULL,
    CONSTRAINT pk_document_properties PRIMARY KEY (id),
    CONSTRAINT fk_document_properties_document FOREIGN KEY (document_id)
        REFERENCES document (id) ON DELETE CASCADE
);

CREATE INDEX ix_document_properties_document ON document_properties (document_id, position);

CREATE TABLE document_relations (
    id                 UUID         NOT NULL DEFAULT uuidv7(),
    document_id        UUID         NOT NULL,
    relation_key       VARCHAR(100) NOT NULL,
    target_document_id UUID         NOT NULL,
    position           INTEGER      NOT NULL,
    CONSTRAINT pk_document_relations PRIMARY KEY (id),
    CONSTRAINT uq_document_relations UNIQUE (document_id, relation_key, target_document_id),
    CONSTRAINT fk_document_relations_document FOREIGN KEY (document_id)
        REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT fk_document_relations_target FOREIGN KEY (target_document_id)
        REFERENCES document (id) ON DELETE CASCADE
);

CREATE INDEX ix_document_relations_target ON document_relations (target_document_id);

CREATE TABLE document_versions (
    id                 UUID         NOT NULL DEFAULT uuidv7(),
    document_id        UUID         NOT NULL,
    source_revision_no BIGINT       NOT NULL,
    kind               VARCHAR(20)  NOT NULL,
    label              VARCHAR(200),
    snapshot           JSONB        NOT NULL,
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_document_versions PRIMARY KEY (id),
    CONSTRAINT fk_document_versions_document FOREIGN KEY (document_id)
        REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT ck_document_versions_kind
        CHECK (kind IN ('AUTO', 'NAMED', 'AI_APPLY', 'RESTORE', 'REFRESH_BASE'))
);

CREATE INDEX ix_document_versions_document_created ON document_versions (document_id, created_at DESC);
CREATE INDEX ix_document_versions_expires_at ON document_versions (expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE refresh_runs (
    id             UUID         NOT NULL DEFAULT uuidv7(),
    project_id     UUID         NOT NULL,
    requested_by   UUID         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    prompt_version VARCHAR(100) NOT NULL,
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ready_at       TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    error_message  TEXT,
    CONSTRAINT pk_refresh_runs PRIMARY KEY (id),
    CONSTRAINT fk_refresh_runs_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT ck_refresh_runs_status CHECK (status IN (
        'CAPTURING_BASE', 'GENERATING', 'READY', 'APPLYING', 'APPLIED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_refresh_runs_project_started ON refresh_runs (project_id, started_at DESC);
CREATE UNIQUE INDEX uq_refresh_runs_one_in_progress ON refresh_runs (project_id)
    WHERE status IN ('CAPTURING_BASE', 'GENERATING');

CREATE TABLE refresh_document_drafts (
    id                      UUID        NOT NULL DEFAULT uuidv7(),
    refresh_run_id          UUID        NOT NULL,
    target_document_id      UUID        NOT NULL,
    base_version_id         UUID        NOT NULL,
    left_source_revision_no BIGINT,
    left_snapshot           JSONB,
    right_snapshot          JSONB,
    status                  VARCHAR(20) NOT NULL DEFAULT 'BASE_CAPTURED',
    draft_revision          BIGINT      NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_refresh_document_drafts PRIMARY KEY (id),
    CONSTRAINT uq_refresh_document_drafts_run_target UNIQUE (refresh_run_id, target_document_id),
    CONSTRAINT fk_refresh_document_drafts_run FOREIGN KEY (refresh_run_id)
        REFERENCES refresh_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_document_drafts_target FOREIGN KEY (target_document_id)
        REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_document_drafts_base_version FOREIGN KEY (base_version_id)
        REFERENCES document_versions (id),
    CONSTRAINT ck_refresh_document_drafts_status
        CHECK (status IN ('BASE_CAPTURED', 'OPEN', 'APPLIED', 'STALE', 'SKIPPED')),
    CONSTRAINT ck_refresh_document_drafts_snapshots CHECK (
        status NOT IN ('OPEN', 'APPLIED', 'STALE')
        OR (left_source_revision_no IS NOT NULL
            AND left_snapshot IS NOT NULL
            AND right_snapshot IS NOT NULL))
);

CREATE INDEX ix_refresh_document_drafts_target ON refresh_document_drafts (target_document_id);
CREATE INDEX ix_refresh_document_drafts_base_version ON refresh_document_drafts (base_version_id);

CREATE TABLE outbox_events (
    id           UUID         NOT NULL DEFAULT uuidv7(),
    event_type   VARCHAR(100) NOT NULL,
    aggregate_id UUID         NOT NULL,
    payload      JSONB        NOT NULL,
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
);

CREATE INDEX ix_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
