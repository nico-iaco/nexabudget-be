# API and Features Guide

## Core Features Overview

NexaBudget offers a robust set of features to manage personal finances, integrate with banking institutions, and leverage AI for insights.

### 1. Account and Transaction Management

* **Manual Accounts:** Users can create manual accounts and track transactions (Income, Expense, Transfer).
* **Open Banking (GoCardless):** Users can link real bank accounts via GoCardless.
  * Background syncing keeps transactions up-to-date.
  * Sync uses distributed locks to prevent race conditions.
* **Multi-Currency:** Automatic exchange rate retrieval for transactions moving between accounts of different currencies.
* **Soft Deletes & Trash:** Deleting an account or transaction moves it to the Trash (soft delete). A scheduled task purges items older than 30 days.

### 2. Crypto Portfolio (Binance + Coinbase)

* **Binance Sync:** Users can provide read-only Binance API Keys to sync their spot balances.
* **Coinbase Sync:** Users can provide Coinbase Advanced Trade credentials (API Key Name + Private Key) to sync spot balances across accounts and portfolios.
* **Holdings Tracking:** Crypto balances are stored with a source (MANUAL, BINANCE, COINBASE) alongside traditional fiat accounts.
* **Pricing Cache:** Real-time crypto prices are cached in Valkey/Redis for 5 minutes.

### 3. Budgeting & Alerts

* **Budgets & Templates:** Create budgets for specific categories. Templates allow recurring budget creation.
* **Budget Alerts:** Set percentage-based thresholds on budgets. The system will trigger alerts (e.g., via Email) when spending exceeds limits.

### 4. Reports & Dashboard

* **Monthly trend:** `GET /api/reports/monthly-trend?months=12` — income/expense series.
* **Category breakdown:** `GET /api/reports/category-breakdown?startDate=&endDate=` — returns `CategoryBreakdownItem { net, percentage, inferredType (IN if net>0, OUT if net<0) }`. The legacy `type` filter has been removed.
* **Month-over-month comparison:** `GET /api/reports/month-comparison?year=&month=`.
* **Projection:** `GET /api/reports/monthly-projection`.
* **Budget monthly summary (dashboard widget):** `GET /api/budgets/monthly-summary?date=` returns one row per active budget for the reference month with `limit`, `spent` (net OUT−IN, may be negative), `remaining`, `percentageUsed`, period bounds.

### 5. AI Integrations (Google Gemini via Spring AI)

* **Auto-Categorization:** new and imported transactions are sent to Gemini (`gemini-2.5-flash-lite` family / configurable via `NEXABUDGET_CHAT_MODEL`) to derive a category.
* **AI Reports (asynchronous):**
  * `POST /api/reports/ai-analysis` — enqueues a job (time range capped at 1 year), returns a `jobId` and `PENDING` status. The transaction dataset is attached as a real multipart `.csv` (Spring AI Media Attachment), not embedded in the prompt.
  * `GET /api/reports/ai-analysis/{jobId}` — polls the job; on completion returns the generated PDF (rendered via OpenPDF).
* **Financial Chatbot (`/api/chat`):** persistent `ChatSession`/`ChatMessage` history on PostgreSQL, Gemini tool-calling enabled so the model can query the user's data.
* **Semantic Caching:** queries are embedded with `gemini-embedding-001` (3072 dims) and similarity-searched in MongoDB Atlas (`semantic_cache` collection) before hitting Gemini, cutting cost and latency.

### 6. CSV / OFX Import

Two-step flow under `POST /api/accounts/{id}/import/…`:

1. **Preview** — `/csv/preview` or `/ofx/preview`: parses the file, returns the rows the user would import.
2. **Confirm** — `/csv` or `/ofx`: persists the rows and triggers AI auto-categorization.

Deduplication uses SHA-256 of `(accountId|date|amount|description)` stored in `transactions.import_hash`, plus the external `FITID` (`external_id`). Parsers: Apache Commons CSV (configurable `CsvColumnMapping`); OFX 1.x SGML / 2.x XML via regex.

## API Structure

The API is exposed via 16 REST controllers, secured by JWT or `X-Api-Key` (see [SECURITY.md](SECURITY.md)).

| Controller | Base Path | Responsibility |
| :--- | :--- | :--- |
| `AuthController` | `/api/auth` | Login, registration, JWT issuance. Rate-limited. |
| `UserController` | `/api/users` | User profile, `defaultCurrency`, password change. |
| `ApiKeyController` | `/api/api-keys` | M2M API keys (plaintext returned only on creation). |
| `AccountController` | `/api/accounts` | CRUD on accounts (manual & GoCardless-linked). |
| `TransactionController` | `/api/transactions` | CRUD on transactions. Paged: `GET /paged?page=&size=`. |
| `CategoryController` | `/api/categories` | User categories; uniqueness on `(user, name)`. |
| `BudgetController` | `/api/budgets` | Budgets per category; `monthly-summary?date=` for dashboard. |
| `BudgetAlertController` | `/api/budget-alerts` | Per-budget threshold (1–100%); one email per period. |
| `BudgetTemplateController` | `/api/budget-templates` | Recurring budgets (MONTHLY/QUARTERLY/YEARLY). |
| `GocardlessController` | `/api/gocardless` | Bank link flow & sync trigger. |
| `CryptoPortfolioController` | `/api/crypto` | Binance + Coinbase holdings & portfolio value. |
| `ChatController` | `/api/chat` | NexaBot — Gemini chat with tool-calling, persistent sessions. |
| `ReportController` | `/api/reports` | Reports + async AI analysis (`/ai-analysis`, `/ai-analysis/{jobId}`). |
| `ImportController` | `/api/accounts/{accountId}/import` | CSV / OFX preview + confirm. |
| `TrashController` | `/api/trash` | List & restore soft-deleted items; auto-purged after 30 days. |
| `AuditLogController` | `/api/audit-log` | Read-only audit trail. |
