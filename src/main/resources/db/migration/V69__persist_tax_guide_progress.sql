ALTER TABLE tax_conversation_state
    ADD COLUMN guide_depth SMALLINT NOT NULL DEFAULT 0 CHECK (guide_depth BETWEEN 0 AND 2),
    ADD COLUMN verification_started BOOLEAN NOT NULL DEFAULT false;

UPDATE tax_conversation_state state
SET verification_started = true
WHERE EXISTS (
    SELECT 1
    FROM chat_room room
    JOIN tax_document document ON document.user_id = room.user_id
    WHERE room.id = state.room_id
      AND document.deleted_at IS NULL
);
