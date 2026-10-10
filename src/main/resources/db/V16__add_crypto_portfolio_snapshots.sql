-- Snapshot giornaliero del valore del portafoglio crypto: serve allo storico del patrimonio netto
-- (le crypto non hanno uno storico ricostruibile dai soli holdings attuali).
-- Da applicare manualmente in produzione (ddl-auto=validate).
CREATE TABLE crypto_portfolio_snapshots
(
    id            UUID           NOT NULL PRIMARY KEY,
    user_id       UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    snapshot_date DATE           NOT NULL,
    total_value   NUMERIC(19, 4) NOT NULL,
    currency      VARCHAR(3)     NOT NULL,
    CONSTRAINT uk_crypto_snapshot_user_date UNIQUE (user_id, snapshot_date)
);
