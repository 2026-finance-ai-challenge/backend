CREATE TABLE watchlist_item (
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    security_id UUID NOT NULL REFERENCES security (id) ON DELETE CASCADE,
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, security_id)
);

CREATE INDEX watchlist_item_security_idx ON watchlist_item (security_id, user_id);

CREATE TABLE recently_viewed_item (
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    item_type VARCHAR(16) NOT NULL,
    reference_id VARCHAR(128) NOT NULL,
    stock_code VARCHAR(6),
    viewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, item_type, reference_id),
    CONSTRAINT recently_viewed_item_type CHECK (item_type IN ('STOCK', 'NEWS', 'FILING')),
    CONSTRAINT recently_viewed_stock_code CHECK (
        stock_code IS NULL OR stock_code ~ '^[0-9A-Z]{6}$'
    )
);

CREATE INDEX recently_viewed_item_user_time_idx
    ON recently_viewed_item (user_id, viewed_at DESC, item_type, reference_id);

CREATE TABLE user_notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    notification_type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    reference_type VARCHAR(16),
    reference_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT user_notification_type CHECK (
        notification_type IN ('NEWS', 'DISCLOSURE', 'TRADING_CAUTION', 'TAX', 'SYSTEM')
    ),
    CONSTRAINT user_notification_reference_type CHECK (
        reference_type IS NULL OR reference_type IN ('STOCK', 'NEWS', 'FILING', 'TAX')
    )
);

CREATE INDEX user_notification_user_created_idx
    ON user_notification (user_id, created_at DESC, id DESC);

CREATE INDEX user_notification_unread_idx
    ON user_notification (user_id, created_at DESC)
    WHERE read_at IS NULL;
