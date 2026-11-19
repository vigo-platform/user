ALTER TABLE coach_plans
    DROP CONSTRAINT coach_plans_compare_at_price_check,
    ADD CONSTRAINT coach_plans_compare_at_price_check CHECK (compare_at_price >= price);
