ALTER TABLE coach_plans RENAME COLUMN price_rial TO price;
ALTER TABLE coach_plans RENAME COLUMN compare_at_price_rial TO compare_at_price;
ALTER TABLE coach_plans RENAME CONSTRAINT coach_plans_price_rial_check TO coach_plans_price_check;

ALTER TABLE coach_plans
    DROP CONSTRAINT coach_plans_currency_check,
    ADD CONSTRAINT coach_plans_currency_check CHECK (currency ~ '^[A-Z]{3}$'),
    ADD COLUMN is_archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Legacy inactive plans were archived by their coaches. Grandfather their activation
-- so restoring a legacy plan preserves its previous behavior and visibility.
UPDATE coach_plans SET is_archived = TRUE, is_active = TRUE WHERE NOT is_active;

DROP INDEX coach_plans_coach_active_price_idx;
CREATE INDEX coach_plans_coach_active_price_idx ON coach_plans (coach_user_id, currency, price, id)
    WHERE is_active AND NOT is_archived;
