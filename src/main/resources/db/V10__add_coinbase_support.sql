CREATE TABLE user_coinbase_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    api_key_name TEXT NOT NULL,
    private_key TEXT NOT NULL,
    UNIQUE(user_id)
);
