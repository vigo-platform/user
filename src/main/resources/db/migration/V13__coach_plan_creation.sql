ALTER TABLE coach_plans ALTER COLUMN is_active SET DEFAULT TRUE;

ALTER TABLE plan_field_definitions
    DROP CONSTRAINT plan_field_definitions_value_type_check,
    ADD CONSTRAINT plan_field_definitions_value_type_check
        CHECK (value_type IN ('INTEGER', 'SINGLE_SELECT', 'MULTI_SELECT', 'TEXT'));
