-- Tracking investimenti (ETF / azioni / obbligazioni / fondi): asset, operazioni e snapshot giornalieri del valore.
-- Da applicare manualmente in produzione (ddl-auto=validate).

CREATE TABLE investment_assets
(
    id                  UUID           NOT NULL PRIMARY KEY,
    user_id             UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    asset_type          VARCHAR(16)    NOT NULL,
    name                VARCHAR(255)   NOT NULL,
    isin                VARCHAR(12),
    symbol              VARCHAR(32),
    currency            VARCHAR(3)     NOT NULL,
    price_source        VARCHAR(16)    NOT NULL,
    manual_price        NUMERIC(19, 6),
    manual_price_at     TIMESTAMP,
    last_price          NUMERIC(19, 6),
    last_price_currency VARCHAR(3),
    last_price_at       TIMESTAMP,
    coupon_rate         NUMERIC(9, 6),
    coupon_frequency    VARCHAR(16),
    maturity_date       DATE,
    created_at          TIMESTAMP      NOT NULL,
    CONSTRAINT investment_assets_type_check CHECK (asset_type IN ('ETF', 'STOCK', 'BOND', 'FUND', 'OTHER')),
    CONSTRAINT investment_assets_price_source_check CHECK (price_source IN ('YAHOO', 'TWELVE_DATA', 'MANUAL')),
    CONSTRAINT investment_assets_coupon_frequency_check CHECK (coupon_frequency IS NULL OR coupon_frequency IN ('ANNUAL', 'SEMIANNUAL', 'QUARTERLY'))
);

CREATE INDEX idx_investment_assets_user ON investment_assets (user_id);
CREATE UNIQUE INDEX uk_investment_assets_user_isin ON investment_assets (user_id, isin) WHERE isin IS NOT NULL;
CREATE UNIQUE INDEX uk_investment_assets_user_symbol ON investment_assets (user_id, symbol) WHERE symbol IS NOT NULL;

CREATE TABLE investment_operations
(
    id             UUID           NOT NULL PRIMARY KEY,
    asset_id       UUID           NOT NULL REFERENCES investment_assets (id) ON DELETE CASCADE,
    user_id        UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type           VARCHAR(16)    NOT NULL,
    operation_date DATE           NOT NULL,
    quantity       NUMERIC(28, 10),
    price          NUMERIC(19, 6),
    amount         NUMERIC(19, 4),
    fees           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    notes          VARCHAR(500),
    created_at     TIMESTAMP      NOT NULL,
    CONSTRAINT investment_operations_type_check CHECK (type IN ('BUY', 'SELL', 'DIVIDEND', 'COUPON'))
);

CREATE INDEX idx_investment_operations_asset_date ON investment_operations (asset_id, operation_date);
CREATE INDEX idx_investment_operations_user_date ON investment_operations (user_id, operation_date);

CREATE TABLE investment_portfolio_snapshots
(
    id            UUID           NOT NULL PRIMARY KEY,
    user_id       UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    snapshot_date DATE           NOT NULL,
    market_value  NUMERIC(19, 4) NOT NULL,
    cost_basis    NUMERIC(19, 4) NOT NULL,
    currency      VARCHAR(3)     NOT NULL,
    CONSTRAINT uk_investment_snapshot_user_date UNIQUE (user_id, snapshot_date)
);
