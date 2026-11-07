-- Existing accounts keep their current non-2FA behavior until explicitly enrolled.
-- Identifier verification is separate from two-factor enrollment.
ALTER TABLE users
    ADD COLUMN two_factor_authentication BOOLEAN NOT NULL DEFAULT FALSE;
