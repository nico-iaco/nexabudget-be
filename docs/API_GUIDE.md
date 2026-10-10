# API and Features Guide

## Core Features Overview

NexaBudget offers a robust set of features to manage personal finances, integrate with banking institutions, and leverage AI for insights.

### 1. Account and Transaction Management

* **Manual Accounts:** Users can create manual accounts and track transactions (Income, Expense, Transfer).
* **Open Banking — multi-provider (GoCardless + Enable Banking):** Users can link real bank accounts
  via either **GoCardless** (fronted by an external `gocardless-integrator` microservice) or
  **Enable Banking** (Cloud API called directly, JWT RS256 auth — see [ENABLE_BANKING_SETUP.md](ENABLE_BANKING_SETUP.md)).
  A provider-agnostic `BankAggregationProvider` strategy (see [ARCHITECTURE.md](ARCHITECTURE.md))
  lets `AccountService` dispatch to whichever provider an `Account` is linked to (`Account.provider`).
  * Background syncing keeps transactions up-to-date for both providers.
  * Sync uses a database-level atomic lock to prevent race conditions; a lock left by an interrupted sync expires after 1 hour.
  * Each sync re-reads the last 7 days before the previous sync to catch late-booked transactions (duplicates are filtered by external id). A provider error fails the sync (no balance alignment, retried on the next call) instead of being treated as "no new transactions".
  * `requiresReauth` on `AccountResponse` signals an expired consent/session for either provider —
    the frontend re-runs the link flow to clear it.
* **Multi-Currency:** Automatic exchange rate retrieval for transactions moving between accounts of different currencies.
* **Soft Deletes & Trash:** Deleting an account or transaction moves it to the Trash (soft delete). A scheduled task purges items older than 30 days. Restoring a transfer leg restores both legs; restoring a transaction whose account is in the Trash returns 409 (restore the account first); restoring an account restores only the transactions deleted together with it.

### 2. Crypto Portfolio (Binance + Coinbase)

* **Binance Sync:** Users can provide read-only Binance API Keys to sync their spot balances.
* **Coinbase Sync:** Users can provide Coinbase Advanced Trade credentials (API Key Name + Private Key) to sync spot balances across accounts and portfolios.
* **Holdings Tracking:** Crypto balances are stored with a source (MANUAL, BINANCE, COINBASE) alongside traditional fiat accounts.
* **Pricing Cache:** Real-time crypto prices are cached in Valkey/Redis for 5 minutes.

### 2b. Investments (ETF / stocks / bonds / funds) & Net Worth

Investments are tracked by **operations** and live apart from the bank accounts: they never create transactions, so income/expense reports are unchanged.

* **Assets:** `POST/GET/PUT/DELETE /api/investments/assets` — type `ETF | STOCK | BOND | FUND | OTHER`, optional ISIN, ticker (`symbol`, Yahoo notation e.g. `VWCE.DE`, `ENEL.MI`, `AAPL`), currency (derived from the quote if omitted), `priceSource` (`YAHOO` | `TWELVE_DATA` | `MANUAL`). Bonds also carry `couponRate` (% per year), `couponFrequency`, `maturityDate`.
* **Search:** `GET /api/investments/search?q=` — by ticker, name or ISIN (Yahoo, then OpenFIGI for ISINs). An ISIN can have several listings: the client picks one and posts its `symbol`.
* **Operations:** `POST/GET /api/investments/assets/{id}/operations`, `PUT/DELETE /api/investments/operations/{id}` — `BUY`/`SELL` (`quantity`, `price`, optional `fees`) or `DIVIDEND`/`COUPON` (net `amount`). A sale larger than the held quantity — including one made invalid by editing/deleting an earlier purchase — returns 409.
* **Bonds** are priced in **% of face value**: `quantity` = face value (e.g. 10000), `price` = quotation (e.g. 98.5), value = quantity × price / 100. Italian BTP have no free price feed: use `priceSource=MANUAL` and `PUT /api/investments/assets/{id}/manual-price`.
* **Portfolio:** `GET /api/investments/portfolio?currency=` — per position quantity, average price, cost, market value, unrealized/realized P/L, income; totals and allocation by type/currency. Amounts are converted at the current rate; `price`/`avgPrice` stay in the asset currency. `complete=false` means at least one position had no price or exchange rate and is excluded from the totals; `stale=true` on a position means the provider didn't answer and the last known (or manual) price was used.
* **History / performance:** `GET /api/investments/portfolio/history?months=` (daily snapshots, available from when the feature was enabled), `GET /api/investments/performance?startDate=&endDate=` (invested, divested, realized P/L, income, total gain; `startValue`/`totalGain` are null when the opening value can't be reconstructed).
* **Price freshness:** a quote stays in cache for 1 day by default (`NEXABUDGET_MARKET_PRICE_CACHE_TTL`) and prices are fetched on demand when the portfolio is read, never in the background: a price can be up to ~24h old. `priceAsOf` is the market time of the quote (use it in the UI); `stale=true` is a different thing (the provider failed and the last known/manual price is used). The computed portfolio is cached 15 min and refreshed by any write (asset, operation, manual price). An asset without operations has no open position and is not priced.
* **Prices:** Yahoo Finance (unofficial, no key) is the primary source; it can answer 429 under load, in which case the app pauses calls for a couple of minutes and shows the last known price (`stale`). Optional: `TWELVEDATA_API_KEY` (free tier: US listings only) and `OPENFIGI_API_KEY`.
* **Net worth:** `GET /api/net-worth?currency=` — liquidity + crypto + investments with percentages; unavailable components are `null` and listed in `warnings`. `possibleDoubleCounting` is true when the user has both `INVESTIMENTO` accounts and investment assets. `GET /api/net-worth/history?months=` (also `GET /api/reports/net-worth-trend`) is monthly liquidity + crypto + investments. Crypto and investments have history only from the first daily snapshot (no backfill): earlier months have that item `null`, counted as 0 in the total.

### 3. Budgeting & Alerts

* **Budgets & Templates:** Create budgets for specific categories. Templates allow recurring budget creation.
* **Budget Alerts:** Set percentage-based thresholds on budgets. The system will trigger alerts (e.g., via Email) when spending exceeds limits.

### 4. Reports & Dashboard

> **Report semantics — net per category.** All report endpoints below (trend, breakdown, comparison, projection) work on the **net per category** (`OUT − IN` within each category): a category with a positive net counts as expense, a negative net as income; uncategorized transactions count by type. A refund in the same category as the expense reduces that expense instead of inflating income. Transfers are always excluded. Totals are therefore consistent across endpoints.

* **Monthly trend:** `GET /api/reports/monthly-trend?months=12` — net income/expense series per month.
* **Category breakdown:** `GET /api/reports/category-breakdown?startDate=&endDate=` — returns `CategoryBreakdownItem { net, percentage, inferredType (IN if net>0, OUT if net<0) }`. The legacy `type` filter has been removed.
* **Month-over-month comparison:** `GET /api/reports/month-comparison?year=&month=` (`month` must be 1–12, otherwise 400).
* **Projection:** `GET /api/reports/monthly-projection` — end-of-month net income/expense projection from the average of the last 3 months with data (fallback: current month's daily rate).
* **Investments:** `GET /api/reports/investment-performance?startDate=&endDate=` and `GET /api/reports/net-worth-trend?months=` (see section 2b). The AI report and its PDF include a "Patrimonio e investimenti" part when the user has crypto or investments.
* **Budget monthly summary (dashboard widget):** `GET /api/budgets/monthly-summary?date=` returns one row per active budget for the reference month with `limit`, `spent` (net OUT−IN, may be negative), `remaining`, `percentageUsed`, period bounds.

### 5. AI Integrations (Google Gemini via Spring AI)

* **Auto-Categorization:** new and imported transactions are sent to Gemini (`gemini-2.5-flash-lite` family / configurable via `NEXABUDGET_CHAT_MODEL`) to derive a category.
* **AI Reports (asynchronous):**
  * `POST /api/reports/ai-analysis` — enqueues a job (time range capped at 1 year), returns a `jobId` and `PENDING` status (or the cached `COMPLETED` result directly, without regenerating it). No dataset is attached: the model fetches aggregates and the period's transactions through tool calling (`FinanceTools`).
  * `GET /api/reports/ai-analysis/{jobId}` — polls the job; on completion returns the generated PDF (rendered via OpenPDF).
  * Sample output: [`docs/examples/report_finanziario_esempio.pdf`](examples/report_finanziario_esempio.pdf) — fictional data, rendered by the real `AiReportPdfService`. Regenerate it after layout changes with `./mvnw test -Dtest=AiReportSamplePdfGenerator -Dsample.pdf.out=docs/examples/report_finanziario_esempio.pdf`.
* **Financial Chatbot (`/api/chat`):** persistent `ChatSession`/`ChatMessage` history on PostgreSQL, Gemini tool-calling enabled so the model can query the user's data (including net worth and investments: `getNetWorth`, `getInvestmentPortfolio`, `getInvestmentOperations`, `getInvestmentPerformance`; the same 23 read-only tools are published on the MCP server).
* **Semantic Caching:** queries are embedded with `gemini-embedding-001` (3072 dims) and similarity-searched in MongoDB Atlas (`semantic_cache` collection) before hitting Gemini, cutting cost and latency.

### 6. CSV / OFX Import

Two-step flow under `POST /api/accounts/{id}/import/…`:

1. **Preview** — `/csv/preview` or `/ofx/preview`: parses the file, returns the rows the user would import.
2. **Confirm** — `/csv` or `/ofx`: persists the rows and triggers AI auto-categorization.

Deduplication uses SHA-256 of `(accountId|date|amount|description)` stored in `transactions.import_hash` (identical rows in the same file get an occurrence suffix, so repeated legitimate payments are all imported), plus the external `FITID` (`external_id`, scoped to the account and including trashed rows). Parsers: Apache Commons CSV (configurable `CsvColumnMapping`; amounts with thousands separators such as `1.234,56` or `1,234.56` are supported); OFX 1.x SGML / 2.x XML via regex. On confirm, `selectedHashes` null or empty imports all non-duplicate rows.

## Bank Aggregation (GoCardless + Enable Banking)

`BankingController` (`/api/banking/{provider}/...`) is the unified entrypoint for both providers,
where `{provider}` is `gocardless` or `enable-banking`. The legacy `GocardlessController`
(`/api/gocardless/...`) still works unchanged and is **deprecated** — new integrations should target
`/api/banking/gocardless/...` instead.

| Endpoint | Method | Purpose |
| :--- | :--- | :--- |
| `/{provider}/banks?countryCode=` | GET | List supported banks/ASPSPs. For Enable Banking, each `id` encodes `"<name>|<country>"` — pass it back verbatim, don't reconstruct it. |
| `/{provider}/link` | POST | `{ institutionId, localAccountId }` → `{ redirectUrl, providerReference }`. Opens the bank consent redirect. |
| `/{provider}/{localAccountId}/session` | POST | `{ code }` → `{ providerReference, accounts }`. **Enable Banking only** — exchanges the callback `code` for a session. No-op for GoCardless. |
| `/{provider}/{localAccountId}/accounts` | GET | `{ providerReference, accounts }`. For GoCardless: poll here until `accounts` is populated. For Enable Banking: always empty — accounts are already returned by `/session`. |
| `/{provider}/{localAccountId}/link` | POST | `{ accountId }` → 200. Links the chosen provider account to the local `Account`. |
| `/{provider}/{localAccountId}/sync` | POST | `{ actualBalance }` → 202 Accepted. Async transaction sync, identical behavior for both providers. |

**Flow difference:** GoCardless is a single-step redirect + poll; Enable Banking is two-step
(redirect → callback `code` → `POST /session`). See [ENABLE_BANKING_SETUP.md](ENABLE_BANKING_SETUP.md#4-the-callback-route-is-a-single-static-frontend-owned-page)
for how the frontend callback page must be implemented (one static route, `state` carries the
`localAccountId`).

## API Structure

The API is exposed via 18 REST controllers, secured by JWT or `X-Api-Key` (see [SECURITY.md](SECURITY.md)).

| Controller | Base Path | Responsibility |
| :--- | :--- | :--- |
| `AuthController` | `/api/auth` | Login, registration, JWT issuance. Rate-limited. |
| `UserController` | `/api/users` | User profile, `defaultCurrency`, password change. |
| `ApiKeyController` | `/api/api-keys` | M2M API keys (plaintext returned only on creation). |
| `AccountController` | `/api/accounts` | CRUD on accounts (manual, GoCardless-linked, or Enable Banking-linked). |
| `TransactionController` | `/api/transactions` | CRUD on transactions. Paged: `GET /paged?page=&size=`. |
| `CategoryController` | `/api/categories` | User categories; uniqueness on `(user, name)`. |
| `BudgetController` | `/api/budgets` | Budgets per category; `monthly-summary?date=` for dashboard. |
| `BudgetAlertController` | `/api/budget-alerts` | Per-budget threshold (1–100%); one email per period. |
| `BudgetTemplateController` | `/api/budget-templates` | Recurring budgets (MONTHLY/QUARTERLY/YEARLY). |
| `BankingController` | `/api/banking/{provider}` | Unified bank link flow & sync trigger — GoCardless + Enable Banking. |
| `GocardlessController` | `/api/gocardless` | **Deprecated** shim, GoCardless-only, kept for frontend compat — see `BankingController` above. |
| `CryptoPortfolioController` | `/api/crypto` | Binance + Coinbase holdings & portfolio value. |
| `InvestmentController` | `/api/investments` | ETF/stock/bond/fund assets, operations, valued portfolio, performance, search. |
| `NetWorthController` | `/api/net-worth` | Net worth (liquidity + crypto + investments) and its monthly history. |
| `ChatController` | `/api/chat` | NexaBot — Gemini chat with tool-calling, persistent sessions. |
| `ReportController` | `/api/reports` | Reports + async AI analysis (`/ai-analysis`, `/ai-analysis/{jobId}`). |
| `ImportController` | `/api/accounts/{accountId}/import` | CSV / OFX preview + confirm. |
| `TrashController` | `/api/trash` | List & restore soft-deleted items; auto-purged after 30 days. |
| `AuditLogController` | `/api/audit-log` | Read-only audit trail. |
