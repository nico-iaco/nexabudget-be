CREATE TABLE chat_sessions
(
    id         UUID         NOT NULL PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id),
    title      VARCHAR(255),
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);

CREATE INDEX idx_chat_sessions_user ON chat_sessions (user_id, updated_at DESC);

CREATE TABLE chat_messages
(
    id            UUID        NOT NULL PRIMARY KEY,
    session_id    UUID        NOT NULL REFERENCES chat_sessions (id) ON DELETE CASCADE,
    role          VARCHAR(16) NOT NULL,
    content       TEXT,
    tool_name     VARCHAR(64),
    tool_args_json TEXT,
    token_count   INTEGER,
    created_at    TIMESTAMP   NOT NULL
);

CREATE INDEX idx_chat_messages_session ON chat_messages (session_id, created_at);
