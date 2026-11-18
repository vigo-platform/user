-- Coach subscription plans. Categories are a set of capabilities rather than a
-- single enum so new combinations can be introduced without a schema change.
CREATE TABLE coach_plans (
    plan_id UUID PRIMARY KEY,
    coach_id UUID NOT NULL,
    title VARCHAR(80) NOT NULL,
    description VARCHAR(1500),
    price BIGINT NOT NULL CHECK (price >= 10000),
    currency VARCHAR(3) NOT NULL DEFAULT 'IRR' CHECK (currency = 'IRR'),
    duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 365),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX coach_plans_coach ON coach_plans (coach_id, status, created_at DESC);
CREATE INDEX coach_plans_public ON coach_plans (status, created_at DESC);

-- Included services. WORKOUT and NUTRITION together are the "combined" plan; the
-- table makes that a data value instead of a hardcoded plan type.
CREATE TABLE coach_plan_includes (
    plan_id UUID NOT NULL REFERENCES coach_plans(plan_id) ON DELETE CASCADE,
    include_key VARCHAR(30) NOT NULL CHECK (include_key IN ('WORKOUT','NUTRITION','SUPPLEMENT','CHAT_SUPPORT')),
    PRIMARY KEY (plan_id, include_key)
);

-- One row per (coach, athlete, plan) so ownership and repeat purchases stay explicit.
CREATE TABLE plan_subscriptions (
    subscription_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES coach_plans(plan_id),
    coach_id UUID NOT NULL,
    athlete_id UUID NOT NULL,
    order_id UUID,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING_PAYMENT','ACTIVE','EXPIRED','CANCELLED')),
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (coach_id <> athlete_id)
);

CREATE UNIQUE INDEX plan_subscriptions_open_order ON plan_subscriptions (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX plan_subscriptions_athlete ON plan_subscriptions (athlete_id, status);
CREATE INDEX plan_subscriptions_coach ON plan_subscriptions (coach_id, status);
