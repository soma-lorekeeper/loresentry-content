CREATE TABLE withdrawal_survey (
    id         UUID        NOT NULL DEFAULT uuidv7(),
    reasons    TEXT[]      NOT NULL DEFAULT '{}',
    comment    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_withdrawal_survey PRIMARY KEY (id),
    CONSTRAINT ck_withdrawal_survey_reasons CHECK (reasons <@ ARRAY[
        'RARELY_USED', 'MISSING_FEATURE', 'HARD_TO_USE', 'UNRELIABLE',
        'SWITCHED_SERVICE', 'PRIVACY', 'PAUSED_WRITING', 'OTHER']::TEXT[]),
    CONSTRAINT ck_withdrawal_survey_answered CHECK (cardinality(reasons) > 0 OR comment IS NOT NULL)
);

CREATE INDEX ix_withdrawal_survey_created ON withdrawal_survey (created_at DESC);
