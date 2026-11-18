-- The included services (workout, nutrition, etc.) have their own catalog.
-- These features describe how a coach delivers and supports a plan.
INSERT INTO plan_feature_catalog
    (code, title, description, category, is_active, display_order, created_at, updated_at)
VALUES
    ('PERSONALIZED_PLAN', 'برنامه شخصی‌سازی‌شده', 'تنظیم برنامه بر اساس شرایط، هدف و محدودیت‌های هر فرد', 'PERSONALIZATION', TRUE, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PERIODIC_PLAN_REVIEW', 'بازبینی دوره‌ای برنامه', 'بررسی برنامه و اصلاح آن در طول دوره', 'FOLLOW_UP', TRUE, 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PROGRESS_TRACKING', 'پیگیری پیشرفت', 'بررسی منظم روند اجرای برنامه و پیشرفت ورزشکار', 'FOLLOW_UP', TRUE, 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('DIRECT_CONSULTATION', 'مشاوره مستقیم با مربی', 'جلسه مشاوره فردی در طول دوره برنامه', 'SUPPORT', TRUE, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PROGRAM_QA', 'پاسخ‌گویی به پرسش‌های برنامه', 'پاسخ به پرسش‌های مرتبط با اجرای برنامه', 'SUPPORT', TRUE, 60, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('HOME_WORKOUT_ADAPTATION', 'مناسب برای تمرین در خانه', 'امکان اجرای برنامه با امکانات معمول خانه', 'FLEXIBILITY', TRUE, 80, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;

-- Fields without a feature apply to the plan as a whole.
INSERT INTO plan_field_definitions
    (feature_id, code, label, help_text, value_type, validation_rules,
     is_required, is_active, display_order, created_at, updated_at)
VALUES
    (NULL, 'PRIMARY_GOALS', 'اهداف اصلی', 'هدف‌هایی که این پلن برای آن‌ها طراحی شده است',
     'MULTI_SELECT', '{"options":[{"code":"FAT_LOSS","label":"کاهش چربی"},{"code":"MUSCLE_GAIN","label":"افزایش عضله"},{"code":"STRENGTH","label":"افزایش قدرت"},{"code":"GENERAL_FITNESS","label":"آمادگی جسمانی"}]}'::jsonb,
     FALSE, TRUE, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'TRAINING_LOCATION', 'محل اجرای تمرین', 'محل‌های مناسب برای اجرای این پلن',
     'MULTI_SELECT', '{"options":[{"code":"GYM","label":"باشگاه"},{"code":"HOME","label":"خانه"},{"code":"OUTDOOR","label":"فضای باز"}]}'::jsonb,
     FALSE, TRUE, 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'WEEKLY_TRAINING_SESSIONS', 'تعداد جلسات تمرین در هفته', 'در صورت وجود برنامه تمرینی تکمیل شود',
     'INTEGER', '{"min":1,"max":7}'::jsonb,
     FALSE, TRUE, 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;

-- Resolve feature IDs by stable codes so generated IDs can differ across databases.
WITH seed (feature_code, code, label, help_text, value_type, validation_rules, is_required, display_order) AS (
    VALUES
        ('PERSONALIZED_PLAN', 'PERSONALIZATION_SCOPE', 'میزان شخصی‌سازی', 'بخش‌هایی که متناسب با شرایط فرد تنظیم می‌شوند',
         'MULTI_SELECT', '{"options":[{"code":"WORKOUT","label":"تمرین"},{"code":"NUTRITION","label":"تغذیه"},{"code":"SCHEDULE","label":"زمان‌بندی"}]}', TRUE, 10),
        ('PERIODIC_PLAN_REVIEW', 'REVIEW_INTERVAL_DAYS', 'فاصله بازبینی برنامه به روز', 'هر چند روز یک‌بار برنامه بررسی و در صورت نیاز اصلاح می‌شود',
         'INTEGER', '{"min":1,"max":90}', TRUE, 10),
        ('PROGRESS_TRACKING', 'CHECK_IN_FREQUENCY', 'تناوب بررسی پیشرفت', 'زمان‌بندی بررسی وضعیت ورزشکار',
         'SINGLE_SELECT', '{"options":[{"code":"WEEKLY","label":"هفتگی"},{"code":"BIWEEKLY","label":"هر دو هفته"},{"code":"MONTHLY","label":"ماهانه"}]}', TRUE, 10),
        ('DIRECT_CONSULTATION', 'CONSULTATION_SESSION_COUNT', 'تعداد جلسات مشاوره', 'تعداد جلسات فردی در طول دوره',
         'INTEGER', '{"min":1,"max":30}', TRUE, 10),
        ('DIRECT_CONSULTATION', 'CONSULTATION_DURATION_MINUTES', 'مدت هر جلسه مشاوره به دقیقه', 'زمان تقریبی هر جلسه',
         'INTEGER', '{"min":10,"max":180}', TRUE, 20),
        ('PROGRAM_QA', 'QA_RESPONSE_HOURS', 'حداکثر زمان پاسخ‌گویی به ساعت', 'زمان تعهدشده برای پاسخ به پرسش‌های مربوط به برنامه',
         'INTEGER', '{"min":1,"max":168}', TRUE, 10),
        ('EDUCATIONAL_RESOURCES', 'RESOURCE_FORMATS', 'نوع محتوای آموزشی', 'قالب‌های محتوایی ارائه‌شده همراه پلن',
         'MULTI_SELECT', '{"options":[{"code":"VIDEO","label":"ویدئو"},{"code":"TEXT","label":"متن"},{"code":"AUDIO","label":"صوت"}]}', TRUE, 10),
        ('HOME_WORKOUT_ADAPTATION', 'HOME_EQUIPMENT_LEVEL', 'تجهیزات لازم در خانه', 'سطح تجهیزات مورد نیاز برای اجرای برنامه در خانه',
         'SINGLE_SELECT', '{"options":[{"code":"NONE","label":"بدون تجهیزات"},{"code":"BASIC","label":"تجهیزات ساده"},{"code":"ADVANCED","label":"تجهیزات کامل"}]}', TRUE, 10)
)
INSERT INTO plan_field_definitions
    (feature_id, code, label, help_text, value_type, validation_rules,
     is_required, is_active, display_order, created_at, updated_at)
SELECT feature.id, seed.code, seed.label, seed.help_text, seed.value_type,
       seed.validation_rules::jsonb, seed.is_required, TRUE, seed.display_order,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM seed
JOIN plan_feature_catalog AS feature ON feature.code = seed.feature_code
ON CONFLICT (code) DO NOTHING;
