# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw clean install

# Run locally
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Native image build (GraalVM)
./mvnw clean package -Pnative

# Start full stack (PostgreSQL, Valkey, MongoDB)
docker compose up -d
```

## Architecture

Standard Spring Boot layered architecture: `controller` → `service` → `repository` → `model`, with DTOs for API contracts.

**Package root:** `it.iacovelli.nexabudgetbe`

**18 controllers:** Auth, User, Account, Transaction, Category, Budget, CryptoPortfolio, Gocardless (deprecated shim), Banking, Trash, Report, BudgetTemplate, BudgetAlert, AuditLog, ApiKey, Import

**Key patterns:**
- All entities use UUID primary keys (`@GeneratedValue(strategy = GenerationType.UUID)`)
- Lombok used throughout for boilerplate
- Caching via Spring Cache + Valkey/Redis (6h TTL for most caches; 5m for crypto prices)
- JWT stateless auth — `JwtAuthenticationFilter` → `JwtTokenProvider`

## External Integrations

| Service | Purpose |
|---------|---------|
| **GoCardless** | Open Banking PSD2 — bank account/transaction sync (via external `gocardless-integrator` microservice) |
| **Enable Banking** | Open Banking PSD2 — second bank aggregation provider, Cloud API called directly (JWT RS256, no intermediary) |
| **Binance** | Crypto portfolio read (API keys AES-encrypted via `CryptoConverter`) |
| **Google Gemini** | Transaction auto-categorization via `gemini-2.5-flash-lite`; embeddings via `gemini-embedding-001` |
| **MongoDB Atlas** | Vector store for semantic caching of AI embeddings |
| **Exchange Rate API** | Real-time currency conversion |
| **Valkey/Redis** | Caching operations and jobs tracking temporary status |
| **Spring Mail** | Budget alert notifications via SMTP |

## Databases

- **PostgreSQL** — primary relational store (Hibernate, `validate` DDL mode — schema must match entities)
- **Valkey/Redis** — Spring Cache layer
- **MongoDB** — vector store for AI semantic cache

## Required Environment Variables

```
DB_URL, DB_PWD
JWT_SECRET                  # min 32 chars, must not equal dev default — app fails to start otherwise
GEMINI_API_KEY
MONGODB_URI
CRYPTO_ENCRYPTION_KEY       # 32+ char, AES key for Binance credentials
REDIS_HOST, REDIS_PORT
REDIS_USERNAME, REDIS_PASSWORD  # optional
REDIS_SSL_ENABLED           # default: false
VIRTUAL_THREADS_ENABLED     # default: true
SMTP_HOST, SMTP_PORT        # optional, defaults for dev
SMTP_USER, SMTP_PWD         # required in prod
MAIL_FROM                   # optional, default: noreply@nexabudget.it
ENABLEBANKING_APP_ID        # Enable Banking application id (control panel)
ENABLEBANKING_PRIVATE_KEY   # RSA private key, PEM PKCS8 ("-----BEGIN PRIVATE KEY-----"); literal "\n" allowed
ENABLEBANKING_REDIRECT_URL  # callback URL registered for the Enable Banking application
ENABLEBANKING_BASE_URL      # optional, default: https://api.enablebanking.com
ENABLEBANKING_CONSENT_VALID_DAYS  # optional, default: 90
```

## Security & Validation Behaviours

- **Password update** must always go through `UserService.updateUserProfile()` — never set `passwordHash` directly on the entity (bypasses BCrypt).
- **Budget ownership**: `updateBudget` and `deleteBudget` in `BudgetController` use `getBudgetByIdAndUser()` — only the owning user can modify a budget.
- **Category uniqueness**: `CategoryService.createCategory()` checks `existsByUserAndName()` before saving and throws `IllegalStateException` on duplicate. Categories are no longer tied to a `TransactionType`.
- **Budget date validation**: `BudgetService.validateBudgetDates()` is called by both `createBudget()` and `updateBudget()` — throws `IllegalArgumentException` if `endDate < startDate`.
- **GoCardless sync race condition**: `AccountService.tryAcquireSyncLock()` issues an atomic `UPDATE … WHERE isSynchronizing = false` via `AccountRepository.markSynchronizing()` — avoids reading and writing the flag in two separate round trips.
- **GoCardless token**: `GocardlessService.generateBankLinkForToken()` returns `Optional<GocardlessCreateWebToken>` — callers must handle `Optional.empty()` (controller throws `503`).
- **Multi-provider bank aggregation**: `Account.provider` (new `BankProvider` enum: `GOCARDLESS`/`ENABLE_BANKING`, nullable — null means never linked/manual account) discriminates which strategy owns a conto. `service/bank/BankAggregationProvider` is the provider-agnostic interface (`getInstitutions`, `startLink`, `completeLink`, `getProviderAccounts`, `fetchTransactions`), implemented by `GocardlessAggregationProvider` (thin adapter over the unchanged `GocardlessService`) and `EnableBankingAggregationProvider` (wraps `EnableBankingService`). `AccountService` holds a `Map<BankProvider, BankAggregationProvider>` built from all registered beans and dispatches on `account.getProvider()` via `resolveProvider()`. Both `requisitionId` and `externalAccountId` on `Account` are generic, provider-agnostic columns reused across providers (GoCardless requisitionId/account id, Enable Banking session_id/account uid). `AccountService.syncAccountTransactions()` is the provider-agnostic sync entrypoint (lock/6h-guard/balance-alignment logic unchanged); `syncAccountTransactionWithGocardless()` / `linkAccountToGocardless()` remain as `@Deprecated` thin aliases for compat. Transaction dedup on import uses `TransactionRepository.findByExternalIdAndAccount()` (scoped to the account, not global) to avoid externalId collisions across providers. New unified endpoints: `GET/POST /api/banking/{provider}/...` (`provider` = `gocardless` | `enable-banking`) in `BankingController`; the legacy `/api/gocardless/**` (`GocardlessController`) is kept as a `@Deprecated` shim, unchanged, for frontend compat.
- **Enable Banking specifics**: unlike GoCardless (fronted by an external `gocardless-integrator` microservice), `EnableBankingService` calls `api.enablebanking.com` directly. Auth is a per-request `Authorization: Bearer <JWT>` — RS256, signed in-app with the `ENABLEBANKING_PRIVATE_KEY` (PKCS8 PEM; literal `\n` in the env var are normalized to real newlines before parsing), cached in memory until shortly before expiry (`kid` header = `ENABLEBANKING_APP_ID`). Link flow has an extra step versus GoCardless: `startLink()` opens the bank consent redirect (no `providerReference` yet); `completeLink()` exchanges the callback `code` for a session (`session_id` → persisted on `Account.requisitionId`) plus the authorized accounts. `getTransactions()` paginates via `continuation_key` (capped at `MAX_TRANSACTION_PAGES=50`, logs a warning if the cap is hit — no silent truncation). A 401/403 from `/accounts/{uid}/transactions` is translated into `BankReauthRequiredException` (parent of `GocardlessRequisitionExpiredException`, so `AccountService`'s catch block handles both providers uniformly) via the same Spring Retry `@Recover` gotcha as GoCardless (see below) — a dedicated `@Recover(BankReauthRequiredException, ...)` just rethrows.
- **Category merge**: `CategoryService.mergeCategories(sourceId, targetId, user)` runs a bulk JPQL `UPDATE` on both `Transaction` and `Budget` tables, then deletes the source category — all in one `@Transactional`. Source must be user-owned (not a default category). No `TransactionType` constraint.
- **Soft delete**: `Transaction` and `Account` use `@SQLRestriction("deleted = false")`. DELETE endpoints now soft-delete (set `deleted=true, deleted_at=now`). Hard delete never happens via normal endpoints. All `@Modifying` native queries use `clearAutomatically = true, flushAutomatically = true` to keep the Hibernate L1 cache consistent. `TransactionRepository.hardDeleteAll()` and `AccountRepository.hardDeleteAll()` exist for test teardown only.
- **Trash recovery**: `GET /api/trash/transactions`, `GET /api/trash/accounts`, `POST /api/trash/{type}/{id}/restore`. `TrashService.purgeExpiredItems()` hard-deletes items older than 30 days (cron `0 0 3 * * ?`).
- **Scheduling**: `@EnableScheduling` is on `AsyncConfig`. Budget template instantiation runs at `0 0 1 1 * ?` (1 AM on 1st of month). Budget alert checks run every hour (`fixedRate = 3_600_000`). Trash purge runs at `0 0 3 * * ?`.
- **Budget templates**: `BudgetTemplate` entity with `RecurrenceType` (MONTHLY/QUARTERLY/YEARLY). `createTemplate()` and `updateTemplate()` always set `startDate = first day of current month` via `upsertCurrentPeriodBudget()` — if an active budget already exists for that user+category it is updated in-place, otherwise a new one is created. The cron job `instantiateTemplates()` at `0 0 1 1 * ?` handles future months. `POST/GET/PUT/DELETE /api/budget-templates`.
- **Budget alerts**: `BudgetAlert` entity stores per-budget threshold (1–100%). Scheduler checks hourly. Notification is sent **once per budget period**: the cooldown check compares `lastNotifiedAt` against `budget.startDate` (not a fixed 24h window) — if `lastNotifiedAt >= startDate` the alert is already consumed for that period. `POST/GET/PUT/DELETE /api/budget-alerts`. Email HTML via `EmailService` (async). Logs prefixed `[BudgetAlert]` trace every step: alerts found, budget missing, usage %, threshold crossed, cooldown active.
- **Budget monthly summary (dashboard)**: `GET /api/budgets/monthly-summary?date=` returns `List<BudgetDto.MonthlySummaryResponse>` — one row per active budget for the reference month. Each row includes `budgetId`, `categoryId`, `categoryName`, `limit`, `spent` (net OUT−IN for that category in the month), `remaining`, `percentageUsed`, `budgetStartDate`, `budgetEndDate`, `periodStart`, `periodEnd`. Reuses `BudgetService.getBudgetUsage()` (backed by `TransactionRepository.sumNetByUserAndCategoryAndDateRange()`). `spent` can be negative when refunds exceed expenses. Designed for the dashboard budget table widget.
- **Financial reports**: `ReportService` uses JPQL `GROUP BY YEAR/MONTH` aggregate queries. Endpoints: `GET /api/reports/monthly-trend?months=12`, `/category-breakdown?startDate=&endDate=` (net breakdown), `/month-comparison?year=&month=`, `/monthly-projection`. **Semantica critica**: `category-breakdown` usa il **netto per categoria** (`SUM(OUT) − SUM(IN)` per categoria, via `findCategoryNetBreakdown`); `monthly-trend`, `month-comparison` e `monthly-projection` usano invece il **lordo separato per tipo** (`SUM(amount) WHERE type=OUT/IN`). Le due viste non sono direttamente confrontabili: la differenza tra i totali è esattamente l'importo dei rimborsi intra-categoria. `CategoryBreakdownResponse` espone `totalExpense` (somma net delle righe OUT) e `totalIncome` (somma net delle righe IN) — la somma delle `CategoryBreakdownItem.net` con `inferredType=OUT` è sempre uguale a `totalExpense`; usare questi campi come "spesa/entrata totale" nella vista breakdown, non i totali di altri endpoint. `grandTotal` è deprecato (valorizzato uguale a `totalExpense` per retro-compatibilità). Categorie con `net=0` (spese interamente rimborsate) compaiono nell'elenco con `percentage=0` e `inferredType=OUT`. `CategoryBreakdownItem` has `net` (absolute value of net), `percentage` (% within its group), `inferredType` (OUT if net≥0, IN if net<0). Nuova implementazione AI Asincrona tramite polling con timeout rate-limite a 1 Anno su endpoint in `AiReportService` (salvata in Valkey cache temporanea): `POST /api/reports/ai-analysis` e `GET /api/reports/ai-analysis/{jobId}`. Modello AI riceve CSV come allegato multipart reale (file `.csv` tramite Spring AI Media Attachment), non concatenato al testo del prompt. La chiamata è asincrona con generazione stato pending.
- **Email Notifications**: `EmailService` gestisce l'invio di email SMTP. In dev usa Mailhog (localhost:1025). Le email usano template HTML inline. Errori nell'invio vengono loggati ma non bloccano i processi chiamanti.
- **Multi-currency transfers**: `TransactionService.createTransfer()` e le funzioni di conversione (`convertTransactionToTransfer`) detects when source/destination account currencies differ, calls `ExchangeRateService.getRate()`, converts the amount, and stores `exchangeRate`, `originalCurrency`, `originalAmount` on the IN transaction. `AccountService.getTotalConvertedBalance()` aggregates the user's balances by converting via `CurrencyConversionService` to the user's `defaultCurrency`.
- **Audit log**: `AuditAspect` (`@Aspect @Component` in `config/`) intercepts service write methods via `@AfterReturning` and records to the `audit_logs` table via `AuditLogService`. User resolved from `SecurityContextHolder`; IP from `RequestContextHolder`. Endpoints: `GET /api/audit-log?page=0&size=20`, `GET /api/audit-log/{entityType}/{entityId}`.
- **API Key auth**: `ApiKey` entity stores SHA-256 hash of the plaintext key (never stored plain). `ApiKeyAuthenticationFilter` reads `X-Api-Key` header, hashes it, looks up in DB, validates active + not expired, updates `lastUsedAt`. Filter runs before `JwtAuthenticationFilter`. Dual auth: JWT session OR API key. Endpoints: `POST/GET/PUT/DELETE /api/api-keys`. Key shown in plaintext **only** at creation.
- **Import CSV/OFX**: `ImportService` parses CSV (via Apache Commons CSV + configurable `CsvColumnMapping`) and OFX 1.x SGML / 2.x XML (regex-based). Deduplication via SHA-256 hash of `(accountId|date|amount|description)` stored in `transactions.import_hash`; also checks `externalId` (FITID). AI auto-categorizes imported rows. Two-step flow: preview (`/csv/preview`, `/ofx/preview`) then confirm (`/csv`, `/ofx`). Endpoints under `POST /api/accounts/{id}/import/…`.

## Resilience & Performance

- **Spring Retry** (`spring-retry:2.0.12` + `aspectjweaver:1.9.25`): `@EnableRetry` is on `AsyncConfig`. `@Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))` applied to all `GocardlessService` and `EnableBankingService` public methods, and `ExchangeRateService.getRate()`. Cached methods use `unless` conditions to prevent caching empty fallback results (which would block retries on subsequent calls).
- **HTTP timeouts**: GoCardless 5 s connect / 10 s read; Enable Banking 5 s connect / 10 s read; Binance 5 s / 5 s; ExchangeRate 5 s / 5 s.
- **Batch crypto prices**: `BinanceService.getAllTickerPricesUsdt()` fetches all prices in a single API call and is used by `CryptoPortfolioService.getPortfolioValue()`. Per-symbol `getTickerPrice()` is used only as fallback for symbols not present in the batch map.
- **Cache warming**: `CacheWarmupRunner` pre-populates exchange rate cache (USD → EUR/GBP) at startup via `CompletableFuture.runAsync()`.

## Key Architecture Notes

- **`BudgetService`** depends directly on `TransactionRepository` (not `TransactionService`) for the net aggregation query `sumNetByUserAndCategoryAndDateRange()`. `getBudgetUsage()` and `getRemainingBudgets()` are `@Transactional(readOnly = true)` to keep the Hibernate session open across lazy-loaded associations.
- **Net category accounting**: `Category` no longer has a `transactionType` field. Budget spent = OUT−IN on the same category within the period. `GET /api/categories/type/{type}` endpoint has been removed (breaking change). DB migration required: `ALTER TABLE categories DROP CONSTRAINT uk_category_user_name_type; ALTER TABLE categories DROP COLUMN transaction_type; ALTER TABLE categories ADD CONSTRAINT uk_category_user_name UNIQUE (user_id, name);`. Before dropping, deduplicate rows with the same `name` per user: remap `transactions.category_id` / `budgets.category_id` to the surviving row, then delete duplicates.
- **Transaction pagination**: `GET /api/transactions/paged?page=0&size=20` returns `Page<TransactionResponse>`. The underlying `findByUserPaged()` uses a split count query to avoid HQL pagination issues with `JOIN FETCH`.
- **DB indexes** are declared via `@Index` on `Transaction` (`user_id+transaction_date`, `account_id+transaction_date`, `category_id`) and `Budget` (`user_id+start_date+end_date`). DDL mode is `validate` in production — new indexes require a separate DB migration script.
- **New DB columns** added by Phase 4: `transactions.deleted` (BOOLEAN NOT NULL DEFAULT false), `transactions.deleted_at` (TIMESTAMP), `accounts.deleted` (BOOLEAN NOT NULL DEFAULT false), `accounts.deleted_at` (TIMESTAMP). New tables: `budget_templates`, `budget_alerts`. These must be migrated manually in production.
- **New DB columns** added by Phase 5: `transactions.exchange_rate` (DECIMAL(20,8)), `transactions.original_currency` (VARCHAR(3)), `transactions.original_amount` (DECIMAL(19,4)), `transactions.import_hash` (VARCHAR(64)). New tables: `audit_logs`, `api_keys`. These must be migrated manually in production.
- **New DB column** added by Phase 6: `accounts.requires_reauth` (BOOLEAN NOT NULL DEFAULT false). Must be migrated manually in production.
- **New DB column** (Enable Banking integration, `db/V12__add_bank_provider_to_accounts.sql`): `accounts.provider` (VARCHAR(32), nullable). Backfills existing GoCardless-linked rows (`requisition_id`/`external_account_id` not null) to `'GOCARDLESS'`. Must be migrated manually in production (`ddl-auto=validate`).
- **GoCardless requisition expired during transaction sync**: `GocardlessService.getGoCardlessTransaction()` inspects `data.error_code`/`data.requisitionStatus` on the integrator response (mapped onto `GocardlessBankTransaction`) and throws `GocardlessRequisitionExpiredException` (e.g. `ITEM_LOGIN_REQUIRED` / `requisitionStatus=EX`) instead of silently returning an empty transaction list. `AccountService.syncAccountTransactionWithGocardless()` catches it, sets `account.requiresReauth=true` and clears it back to `false` on the next successful sync; the call is inside the same `try/finally` that releases `isSynchronizing`, so the sync lock is always released even on this error. `requiresReauth` is exposed on `AccountDto.AccountResponse` — the frontend should prompt the user to call `POST /api/gocardless/bank/link` again when it is `true`. **Gotcha**: any `@Retryable` method that has at least one `@Recover` method always goes through Spring Retry's recovery lookup on the final exception, even for exception types outside `retryFor` — if no `@Recover` signature matches, it fails with `ExhaustedRetryException: Cannot locate recovery method`, masking the real exception. `getGoCardlessTransaction()` therefore needs a dedicated `@Recover(GocardlessRequisitionExpiredException, ...)` that just rethrows — add one for any new custom exception thrown from that method.
- **Schema change (net category accounting)**: `categories.transaction_type` column removed; unique constraint changed from `(user_id, name, transaction_type)` to `(user_id, name)`. Migration script: see "Net category accounting" note above.
- **`commons-csv:1.12.0`** added as a dependency for CSV import parsing.
- **`Budget` uses `@Data`** (Lombok) which generates `hashCode/equals` from all fields including lazy associations. Never use a `Budget` entity as a `HashMap` key outside a Hibernate session — iterate with `entrySet().stream().filter(e -> e.getKey().getId().equals(...))` instead.

## Notable Configuration

- **Virtual threads** enabled by default (`spring.threads.virtual.enabled=true`), using `SimpleAsyncTaskExecutor`
- **GraalVM native** support via `native-maven-plugin`; reflection hints in `GoogleGenAiRuntimeHints` and `NativeRuntimeHints`
- **Production profile** (`application-prod.properties`): INFO logging, restricted actuator health details
- Swagger UI available at `/swagger-ui/` when running locally
- Prometheus metrics at `/actuator/prometheus`
- Hibernate enhancements (lazy init, dirty tracking) applied via Maven plugin at build time
