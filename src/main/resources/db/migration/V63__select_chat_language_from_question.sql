ALTER TABLE chat_generation
    DROP CONSTRAINT chat_generation_answer_locale,
    ALTER COLUMN answer_locale TYPE VARCHAR(4),
    ALTER COLUMN answer_locale SET DEFAULT 'auto',
    ADD CONSTRAINT chat_generation_answer_locale CHECK (answer_locale IN ('en', 'ko', 'auto'));
