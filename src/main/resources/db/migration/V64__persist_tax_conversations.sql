WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY last_message_at DESC, id DESC) AS position
    FROM chat_room WHERE context_type = 'TAX_GUIDE' AND deleted_at IS NULL
)
UPDATE chat_room SET deleted_at = CURRENT_TIMESTAMP, purge_after = CURRENT_TIMESTAMP + interval '30 days'
WHERE id IN (SELECT id FROM ranked WHERE position > 1);

CREATE UNIQUE INDEX uq_chat_room_active_tax ON chat_room(user_id)
    WHERE context_type = 'TAX_GUIDE' AND deleted_at IS NULL;

CREATE TABLE tax_conversation_state (
    room_id UUID PRIMARY KEY REFERENCES chat_room(id) ON DELETE CASCADE,
    locale VARCHAR(2) NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'ko')),
    eligibility JSONB,
    comparison JSONB
);

DROP INDEX uq_tax_document_active_content;
CREATE UNIQUE INDEX uq_tax_document_active_content
    ON tax_document(user_id, document_type, sha256)
    WHERE deleted_at IS NULL AND status IN ('PROCESSING', 'VERIFIED');
