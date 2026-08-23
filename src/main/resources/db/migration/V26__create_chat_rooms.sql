CREATE TABLE chat_room (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    context_type VARCHAR(16) NOT NULL,
    context_reference_id VARCHAR(128),
    context_version VARCHAR(128),
    context_title VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_message_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ,
    CONSTRAINT chat_room_name_length CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT chat_room_context_type CHECK (
        context_type IN ('GENERAL', 'STOCK', 'NEWS', 'FILING', 'TAX_GUIDE')
    ),
    CONSTRAINT chat_room_context_reference CHECK (
        (context_type = 'GENERAL' AND context_reference_id IS NULL)
        OR (context_type IN ('STOCK', 'NEWS', 'FILING') AND context_reference_id IS NOT NULL)
        OR context_type = 'TAX_GUIDE'
    ),
    CONSTRAINT chat_room_filing_version CHECK (
        (context_type = 'FILING' AND context_version IS NOT NULL)
        OR (context_type <> 'FILING' AND context_version IS NULL)
    ),
    CONSTRAINT chat_room_version_nonnegative CHECK (version >= 0),
    CONSTRAINT chat_room_delete_state CHECK (
        (deleted_at IS NULL AND purge_after IS NULL)
        OR (deleted_at IS NOT NULL AND purge_after IS NOT NULL AND purge_after >= deleted_at)
    )
);

CREATE INDEX chat_room_user_recent_idx
    ON chat_room (user_id, last_message_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX chat_room_purge_idx
    ON chat_room (purge_after)
    WHERE purge_after IS NOT NULL;
