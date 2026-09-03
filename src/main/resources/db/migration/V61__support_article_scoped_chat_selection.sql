ALTER TABLE chat_generation DROP CONSTRAINT chat_generation_selection_pair;
ALTER TABLE chat_generation ADD CONSTRAINT chat_generation_selection_text_required
    CHECK (selected_section_id IS NULL OR selected_text IS NOT NULL);
