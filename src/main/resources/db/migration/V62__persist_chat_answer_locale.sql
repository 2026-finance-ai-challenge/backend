ALTER TABLE chat_generation
    ADD COLUMN answer_locale VARCHAR(2) NOT NULL DEFAULT 'en',
    ADD CONSTRAINT chat_generation_answer_locale CHECK (answer_locale IN ('en', 'ko'));
