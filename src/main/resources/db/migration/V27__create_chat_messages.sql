CREATE TABLE chat_message (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES chat_room (id) ON DELETE CASCADE,
    sequence_no BIGINT GENERATED ALWAYS AS IDENTITY,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    client_message_id UUID,
    reply_to_message_id UUID REFERENCES chat_message (id) ON DELETE SET NULL,
    citations JSONB NOT NULL DEFAULT '[]'::JSONB,
    insufficient_evidence BOOLEAN NOT NULL DEFAULT FALSE,
    refusal_reason VARCHAR(1000),
    disclaimer VARCHAR(500),
    confidence NUMERIC(5,4),
    model_id VARCHAR(100),
    prompt_version VARCHAR(100),
    request_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chat_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chat_message_content_length CHECK (char_length(content) BETWEEN 1 AND 20000),
    CONSTRAINT chat_message_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    CONSTRAINT chat_message_user_client_id CHECK (
        (role = 'USER' AND client_message_id IS NOT NULL)
        OR (role = 'ASSISTANT' AND client_message_id IS NULL)
    ),
    CONSTRAINT chat_message_assistant_reply CHECK (
        (role = 'USER' AND reply_to_message_id IS NULL)
        OR (role = 'ASSISTANT' AND reply_to_message_id IS NOT NULL)
    ),
    CONSTRAINT chat_message_room_sequence UNIQUE (room_id, sequence_no)
);

CREATE UNIQUE INDEX chat_message_client_id_idx
    ON chat_message (room_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

CREATE INDEX chat_message_room_history_idx
    ON chat_message (room_id, sequence_no);

CREATE TABLE chat_generation (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES chat_room (id) ON DELETE CASCADE,
    user_message_id UUID NOT NULL REFERENCES chat_message (id) ON DELETE CASCADE,
    assistant_message_id UUID REFERENCES chat_message (id) ON DELETE SET NULL,
    regeneration_of_message_id UUID REFERENCES chat_message (id) ON DELETE SET NULL,
    request_key UUID NOT NULL,
    selected_section_id UUID,
    selected_text VARCHAR(6000),
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100),
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chat_generation_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'STOPPED', 'FAILED')
    ),
    CONSTRAINT chat_generation_attempts_nonnegative CHECK (attempts >= 0),
    CONSTRAINT chat_generation_selection_pair CHECK (
        (selected_section_id IS NULL AND selected_text IS NULL)
        OR (selected_section_id IS NOT NULL AND selected_text IS NOT NULL)
    ),
    CONSTRAINT chat_generation_room_request UNIQUE (room_id, request_key)
);

CREATE INDEX chat_generation_claim_idx
    ON chat_generation (status, available_at, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX chat_generation_room_idx
    ON chat_generation (room_id, created_at DESC);
