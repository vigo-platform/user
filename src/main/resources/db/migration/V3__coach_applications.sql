-- Public coach applications are independent records: submitting one creates no
-- user account and grants no role, and approving one still creates no account.
-- Only a later registration that proves control of the applicant email consumes
-- the approval invitation and gains the COACH role.

CREATE TABLE coach_applications (
 id UUID PRIMARY KEY,
 full_name VARCHAR(60) NOT NULL,
 email VARCHAR(254) NOT NULL,
 phone VARCHAR(16),
 headline VARCHAR(120),
 bio VARCHAR(3000) NOT NULL,
 specialties TEXT NOT NULL,
 experience_years INTEGER NOT NULL CHECK (experience_years BETWEEN 0 AND 60),
 certifications TEXT NOT NULL DEFAULT '[]',
 instagram_handle VARCHAR(30),
 locale VARCHAR(8) NOT NULL DEFAULT 'fa' CHECK (locale IN ('fa','en')),
 status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
 rejection_reason VARCHAR(500),
 reviewed_by UUID,
 reviewed_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 aggregate_version BIGINT NOT NULL DEFAULT 0 CHECK (aggregate_version >= 0)
);

-- At most one open application per email; a rejected or approved applicant may
-- apply again later. Comparison is case-insensitive to match normalised email.
CREATE UNIQUE INDEX coach_applications_pending_email_uq
 ON coach_applications (lower(email)) WHERE status = 'PENDING';

CREATE INDEX coach_applications_status_idx
 ON coach_applications (status, created_at DESC, id);

-- Opaque status-lookup tokens. Only the SHA-256 digest is stored, so a database
-- read cannot replay a token. Applications are never addressable by a bare id.
CREATE TABLE coach_application_tokens (
 token_hash VARCHAR(64) PRIMARY KEY,
 application_id UUID NOT NULL REFERENCES coach_applications(id) ON DELETE CASCADE,
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX coach_application_tokens_application_idx
 ON coach_application_tokens (application_id);

-- Single-use, time-limited invitations bound to the approved applicant email.
-- consumed_at is set at most once, when a registration proves that email.
CREATE TABLE coach_invitations (
 id UUID PRIMARY KEY,
 application_id UUID NOT NULL UNIQUE REFERENCES coach_applications(id),
 email VARCHAR(254) NOT NULL,
 token_hash VARCHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 consumed_at TIMESTAMPTZ,
 consumed_user_id BIGINT REFERENCES users(id),
 CHECK (consumed_at IS NULL OR consumed_user_id IS NOT NULL)
);
CREATE UNIQUE INDEX coach_invitations_active_email_uq
 ON coach_invitations (lower(email)) WHERE consumed_at IS NULL;
CREATE INDEX coach_invitations_email_idx ON coach_invitations (lower(email));
