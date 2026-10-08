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

### 3. Budgeting & Alerts

* **Budgets & Templates:** Create budgets for specific categories. Templates allow recurring budget creation.
* **Budget Alerts:** Set percentage-based thresholds on budgets. The system will trigger alerts (e.g., via Email) when spending exceeds limits.

### 4. Reports & Dashboard

> **Report semantics — net per category.** All report endpoints below (trend, breakdown, comparison, projection) work on the **net per category** (`OUT − IN` within each category): a category with a positive net counts as expense, a negative net as income; uncategorized transactions count by type. A refund in the same category as the expense reduces that expense instead of inflating income. Transfers are always excluded. Totals are therefore consistent across endpoints.

* **Monthly trend:** `GET /api/reports/monthly-trend?months=12` — net income/expense series per month.
* **Category breakdown:** `GET /api/reports/category-breakdown?startDate=&endDate=` — returns `CategoryBreakdownItem { net, percentage, inferredType (IN if net>0, OUT if net<0) }`. The legacy `type` filter has been removed.
* **Month-over-month comparison:** `GET /api/reports/month-comparison?year=&month=` (`month` must be 1–12, otherwise 400).
* **Projection:** `GET /api/reports/monthly-projection` — end-of-month net income/expense projection from the average of the last 3 months with data (fallback: current month's daily rate).
* **Budget monthly summary (dashboard widget):** `GET /api/budgets/monthly-summary?date=` returns one row per active budget for the reference month with `limit`, `spent` (net OUT−IN, may be negative), `remaining`, `percentageUsed`, period bounds.

### 5. AI Integrations (Google Gemini via Spring AI)

* **Auto-Categorization:** new and imported transactions are sent to Gemini (`gemini-2.5-flash-lite` family / configurable via `NEXABUDGET_CHAT_MODEL`) to derive a category.
* **AI Reports (asynchronous):**
  * `POST /api/reports/ai-analysis` — enqueues a job (time range capped at 1 year), returns a `jobId` and `PENDING` status (or the cached `COMPLETED` result directly, without regenerating it). No dataset is attached: the model fetches aggregates and the period's transactions through tool calling (`FinanceTools`).
  * `GET /api/reports/ai-analysis/{jobId}` — polls the job; on completion returns the generated PDF (rendered via OpenPDF).
  * Sample output: [`docs/examples/report_finanziario_esempio.pdf`](examples/report_finanziario_esempio.pdf) — fictional data, rendered by the real `AiReportPdfService`. Regenerate it after layout changes with `./mvnw test -Dtest=AiReportSamplePdfGenerator -Dsample.pdf.out=docs/examples/report_finanziario_esempio.pdf`.
* **Financial Chatbot (`/api/chat`):** persistent `ChatSession`/`ChatMessage` history on PostgreSQL, Gemini tool-calling enabled so the model can query the user's data.
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
| `ChatController` | `/api/chat` | NexaBot — Gemini chat with tool-calling, persistent sessions. |
| `ReportController` | `/api/reports` | Reports + async AI analysis (`/ai-analysis`, `/ai-analysis/{jobId}`). |
| `ImportController` | `/api/accounts/{accountId}/import` | CSV / OFX preview + confirm. |
| `TrashController` | `/api/trash` | List & restore soft-deleted items; auto-purged after 30 days. |
| `AuditLogController` | `/api/audit-log` | Read-only audit trail. |
