-- Coach applications gain structured applicant data.
-- Certificates keep only Storage asset references: no file bytes are stored here.

ALTER TABLE coach_applications ADD COLUMN first_name VARCHAR(60);
ALTER TABLE coach_applications ADD COLUMN last_name VARCHAR(60);
ALTER TABLE coach_applications ADD COLUMN profile_image_file_id UUID;

-- Backfill from the previous single display name. A legacy single-word name keeps
-- an empty last name; the API requires both for new submissions.
UPDATE coach_applications
SET first_name = split_part(trim(full_name), ' ', 1),
    last_name = COALESCE(NULLIF(trim(substr(trim(full_name), length(split_part(trim(full_name), ' ', 1)) + 1)), ''), '')
WHERE first_name IS NULL;

ALTER TABLE coach_applications ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE coach_applications ALTER COLUMN last_name SET NOT NULL;
ALTER TABLE coach_applications ALTER COLUMN first_name SET DEFAULT '';
ALTER TABLE coach_applications ALTER COLUMN last_name SET DEFAULT '';

-- Certificate categories are independent of one another: fields that only make
-- sense for one category stay nullable instead of being forced onto every row.
CREATE TABLE coach_application_certificates (
    certificate_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES coach_applications(id) ON DELETE CASCADE,
    category VARCHAR(20) NOT NULL CHECK (category IN ('UNIVERSITY','FEDERATION','SKILL')),
    title VARCHAR(150) NOT NULL,
    issuer VARCHAR(150),
    field_of_study VARCHAR(150),
    degree VARCHAR(100),
    file_id UUID,
    position INTEGER NOT NULL CHECK (position >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (application_id, position),
    -- University certificates are the only category that carries academic fields.
    CHECK (category = 'UNIVERSITY' OR (field_of_study IS NULL AND degree IS NULL))
);

CREATE INDEX coach_application_certificates_application
    ON coach_application_certificates (application_id, position);

CREATE TABLE coach_application_experiences (
    experience_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES coach_applications(id) ON DELETE CASCADE,
    title VARCHAR(150) NOT NULL,
    organization VARCHAR(150),
    description VARCHAR(1000),
    duration_months INTEGER CHECK (duration_months BETWEEN 0 AND 720),
    position INTEGER NOT NULL CHECK (position >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (application_id, position)
);

CREATE INDEX coach_application_experiences_application
    ON coach_application_experiences (application_id, position);
