-- Correct the V9 catalog and enforce requiredness for future definitions.
UPDATE plan_field_definitions
SET is_required = (feature_id IS NULL), updated_at = CURRENT_TIMESTAMP
WHERE is_required <> (feature_id IS NULL);

ALTER TABLE plan_field_definitions
    ALTER COLUMN is_required DROP DEFAULT,
    ADD CONSTRAINT plan_field_definitions_required_scope_check
        CHECK (is_required = (feature_id IS NULL));

UPDATE plan_field_definitions
SET help_text = 'تعداد جلسات تمرینی که برای هر هفته در نظر گرفته شده است', updated_at = CURRENT_TIMESTAMP
WHERE code = 'WEEKLY_TRAINING_SESSIONS' AND help_text = 'در صورت وجود برنامه تمرینی تکمیل شود';

INSERT INTO plan_field_definitions
    (feature_id, code, label, help_text, value_type, validation_rules, is_required, display_order, created_at, updated_at)
VALUES
    (NULL, 'PLAN_LEVEL', 'سطح برنامه', 'سطح‌هایی که این پلن برای آن‌ها مناسب است', 'MULTI_SELECT',
     '{"options":[{"code":"BEGINNER","label":"مبتدی"},{"code":"INTERMEDIATE","label":"متوسط"},{"code":"ADVANCED","label":"پیشرفته"},{"code":"PROFESSIONAL","label":"حرفه‌ای"}]}'::jsonb,
     TRUE, 40, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Preserve the saved levels in the field's option order before removing the old table.
INSERT INTO coach_plan_field_values (plan_id, field_id, value)
SELECT levels.plan_id, field.id, jsonb_agg(levels.level ORDER BY choice.position)
FROM coach_plan_levels AS levels
CROSS JOIN plan_field_definitions AS field
CROSS JOIN LATERAL jsonb_array_elements(field.validation_rules -> 'options')
    WITH ORDINALITY AS choice(option, position)
WHERE field.code = 'PLAN_LEVEL' AND choice.option ->> 'code' = levels.level
GROUP BY levels.plan_id, field.id;

DROP TABLE coach_plan_levels;
