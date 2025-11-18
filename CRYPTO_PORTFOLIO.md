# Funzionalità Crypto Portfolio

Questa funzionalità permette agli utenti di monitorare il valore del proprio portafoglio di criptovalute.

## Features

- **Inserimento Manuale**: Gli utenti possono aggiungere manualmente le proprie crypto con simbolo e quantità
- **Integrazione Binance**: Gli utenti possono collegare il proprio account Binance per sincronizzare automaticamente i
  propri asset
- **Valore Portfolio**: Calcolo del valore totale del portfolio in USD con i prezzi in tempo reale da Binance

## Endpoints API

### 1. Ottieni valore portfolio

```
GET /api/crypto/portfolio
```

Restituisce il valore totale e i dettagli di ogni asset nel portfolio.

**Response:**

```json
{
  "totalValueUsd": "15000.50",
  "assets": [
    {
      "symbol": "BTC",
      "amount": "0.5",
      "priceUsd": "30000.00",
      "valueUsd": "15000.00"
    }
  ]
}
```

### 2. Aggiungi/Aggiorna holding manuale

```
POST /api/crypto/holdings/manual
```

**Request Body:**

```json
{
  "symbol": "BTC",
  "amount": "0.5"
}
```

### 3. Salva chiavi API Binance

```
POST /api/crypto/binance/keys
```

**Request Body:**

```json
{
  "apiKey": "your-api-key",
  "apiSecret": "your-api-secret"
}
```

Le chiavi vengono salvate in modo sicuro nel database utilizzando crittografia AES.

### 4. Sincronizza da Binance

### 4. Sincronizza da Binance
```
POST /api/crypto/binance/sync
```

Importa automaticamente tutti gli asset dal wallet Binance dell'utente.

**Note:**

- Recupera asset da **TUTTI i wallet** (Spot, Funding, Cross Margin, Isolated Margin, etc.)
- Se il metodo completo fallisce, fa automaticamente fallback al solo wallet Spot
- L'operazione è asincrona e può richiedere qualche secondo
- Gli asset esistenti da Binance vengono sostituiti, quelli manuali rimangono intatti

## Configurazione

### Chiave di Crittografia

Nel file `application.properties`, configura la chiave per crittografare le chiavi API:

```properties
crypto.encryption.key=YourSecureEncryptionKey12345678901234567890
```

**IMPORTANTE**: La chiave deve essere di almeno 32 caratteri per garantire la sicurezza AES-256.

### Binance API

Per ottenere le chiavi API Binance:

1. Accedi al tuo account Binance
2. Vai su Account > API Management
3. Crea una nuova API Key
4. **IMPORTANTE**: Abilita solo il permesso "Enable Reading" (non serve trading)
5. Configura la whitelist degli IP per maggiore sicurezza

## Database

Le tabelle necessarie sono:

### crypto_holdings

```sql
CREATE TABLE crypto_holdings
(
    id      UUID PRIMARY KEY,
    user_id UUID            NOT NULL,
    symbol  VARCHAR(20)     NOT NULL,
    amount  NUMERIC(28, 18) NOT NULL,
    source  VARCHAR(20)     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT unique_user_symbol_source UNIQUE (user_id, symbol, source)
);
```

### user_binance_keys

```sql
CREATE TABLE user_binance_keys
(
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL UNIQUE,
    api_key    TEXT NOT NULL,
    api_secret TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
```

## Caching

I prezzi delle crypto vengono cachati per ridurre le chiamate API a Binance:

- Cache: `crypto-prices`
- TTL: Configurabile in `CacheConfig`

## Sicurezza

- Le chiavi API Binance sono crittografate nel database con AES
- Tutte le API richiedono autenticazione JWT
- Le chiavi API dovrebbero avere solo permessi di lettura su Binance

## Dipendenze

```xml

<dependency>
    <groupId>io.github.binance</groupId>
    <artifactId>binance-connector-java</artifactId>
    <version>3.4.1</version>
</dependency>
```

## Esempio d'uso

1. **Login utente** e ottieni il token JWT
2. **Aggiungi crypto manualmente**:
   ```bash
   curl -X POST http://localhost:8080/api/crypto/holdings/manual \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -d '{"symbol":"ETH","amount":"2.5"}'
   ```

3. **Oppure configura Binance**:
   ```bash
   curl -X POST http://localhost:8080/api/crypto/binance/keys \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -d '{"apiKey":"...","apiSecret":"..."}'
   ```

4. **Sincronizza da Binance**:
   ```bash
   curl -X POST http://localhost:8080/api/crypto/binance/sync \
     -H "Authorization: Bearer <token>"
   ```

5. **Visualizza il portfolio**:
   ```bash
   curl -X GET http://localhost:8080/api/crypto/portfolio \
     -H "Authorization: Bearer <token>"
   ```

## Note

- Gli asset inseriti manualmente e quelli sincronizzati da Binance vengono sommati insieme per calcolare il valore
  totale
- I prezzi sono recuperati in tempo reale da Binance (pair con USDT)
- La sincronizzazione Binance sovrascrive i dati precedenti da Binance, ma mantiene quelli manuali
- I tassi di cambio sono recuperati da ExchangeRate-API e cachati per 6 ore
- Valute supportate: Tutte le valute ISO standard (USD, EUR, GBP, JPY, CAD, AUD, CHF, CNY, etc.)

