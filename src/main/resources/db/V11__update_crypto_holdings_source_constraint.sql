-- Aggiornamento del vincolo check per includere COINBASE come sorgente valida
ALTER TABLE crypto_holdings DROP CONSTRAINT IF EXISTS crypto_holdings_source_check;
ALTER TABLE crypto_holdings ADD CONSTRAINT crypto_holdings_source_check CHECK (source IN ('MANUAL', 'BINANCE', 'COINBASE'));
