-- Resolve old account references before removing the UUID mapping.
CREATE FUNCTION pg_temp.resolve_legacy_user_id(legacy_id UUID)
RETURNS BIGINT LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    account_id BIGINT;
BEGIN
    SELECT id INTO account_id FROM users WHERE public_id = legacy_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No account mapping for legacy user UUID %', legacy_id;
    END IF;
    RETURN account_id;
END;
$$;

ALTER TABLE coach_applications
    ALTER COLUMN reviewed_by TYPE BIGINT USING pg_temp.resolve_legacy_user_id(reviewed_by);

-- The outbox also carries coach application aggregates, whose IDs are still UUIDs.
ALTER TABLE outbox ALTER COLUMN aggregate_id TYPE TEXT USING aggregate_id::TEXT;

-- Upgrade queued events. Retain the payload and key of events already published.
WITH queued AS (
    SELECT id, payload::jsonb AS event,
           CASE WHEN payload::jsonb ->> 'event_type' IN
                     ('CoachApplicationApproved', 'CoachApplicationRejected')
                THEN to_jsonb(aggregate_id)
                ELSE to_jsonb(pg_temp.resolve_legacy_user_id(aggregate_id::UUID))
           END AS new_aggregate_id
    FROM outbox
    WHERE published_at IS NULL
      AND payload::jsonb ->> 'event_type' IN
          ('UserRegistered', 'UserStatusChanged', 'UserRoleChanged', 'CoachApproved', 'CoachRejected',
           'CoachApplicationApproved', 'CoachApplicationRejected')
)
UPDATE outbox
SET aggregate_id = queued.new_aggregate_id #>> '{}',
    payload = (queued.event || jsonb_build_object(
        'aggregate_id', queued.new_aggregate_id,
        'event_version', 2,
        'data', (
            SELECT jsonb_object_agg(field.key,
                CASE WHEN field.key IN ('user_id', 'coach_id', 'reviewed_by', 'changed_by')
                     THEN to_jsonb(pg_temp.resolve_legacy_user_id((field.value #>> '{}')::UUID))
                     ELSE field.value
                END)
            FROM jsonb_each(queued.event -> 'data') AS field
        )
    ))::TEXT
FROM queued
WHERE outbox.id = queued.id;

DROP FUNCTION pg_temp.resolve_legacy_user_id(UUID);
ALTER TABLE users DROP COLUMN public_id;
