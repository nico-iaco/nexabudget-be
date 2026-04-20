# NexaBudget - Backend

This is the backend service for NexaBudget, a personal finance management application. It provides a RESTful API for handling users, accounts, transactions, budgets, and categories.

## Built With

- Java 25
- Spring Boot 4.0.0
- Spring Data JPA
- Spring Security with JWT Authentication
- Maven
- Lombok
- SpringDoc OpenAPI (Swagger UI)
- PostgreSQL
- MongoDB (for Vector Store / Semantic Cache)
- Valkey/Redis (for caching)
- Spring AI with Google Gemini AI (for transaction categorization)
- Spring Boot Actuator with Prometheus
- GraalVM Native Image support

## Database Schema

The application uses a relational database to persist data. All entities use **UUID** as primary keys for better scalability and security. The main entities are:

- **User**: Represents a user of the application.
  - `id` (Primary Key, UUID)
  - `username` (unique)
  - `email` (unique)
  - `password_hash`
  - `created_at`
  - `updated_at`

- **Account**: Represents a financial account (e.g., bank account, credit card).
  - `id` (Primary Key, UUID)
  - `name`
  - `type` (e.g., CASH, CHECKING, SAVINGS)
  - `currency`
  - `user_id` (Foreign Key to User, UUID)
  - `requisition_id` (for GoCardless integration)
  - `external_account_id` (for GoCardless integration)
  - `last_external_sync`
  - `created_at`
  - `deleted` (soft-delete flag, default `false`)
  - `deleted_at` (timestamp of soft deletion)

- **Category**: Represents a category for transactions (e.g., Food, Salary).
  - `id` (Primary Key, UUID)
  - `name`
  - `transaction_type` (INCOME or EXPENSE)
  - `user_id` (Foreign Key to User, UUID, nullable for default categories)

- **Transaction**: Represents a single financial transaction.
  - `id` (Primary Key, UUID)
  - `amount` (BigDecimal)
  - `description`
  - `transaction_date`
  - `type` (INCOME or EXPENSE)
  - `note` (optional text field)
  - `user_id` (Foreign Key to User, UUID)
  - `account_id` (Foreign Key to Account, UUID)
  - `category_id` (Foreign Key to Category, UUID)
  - `transfer_id` (for linked transfers)
  - `external_id` (for GoCardless integration)
  - `created_at`
  - `deleted` (soft-delete flag, default `false`)
  - `deleted_at` (timestamp of soft deletion)
  - `exchange_rate` (nullable — populated on the IN leg of a multi-currency transfer)
  - `original_currency` (nullable — source currency for multi-currency transfers)
  - `original_amount` (nullable — amount in source currency for multi-currency transfers)
  - `import_hash` (nullable — SHA-256 dedup hash for CSV/OFX imports)

- **Budget**: Represents a spending or saving goal for a specific category.
  - `id` (Primary Key, UUID)
  - `budget_limit` (BigDecimal)
  - `start_date`
  - `end_date`
  - `category_id` (Foreign Key to Category, UUID)
  - `user_id` (Foreign Key to User, UUID)
  - `created_at`

- **CryptoHolding**: Represents a cryptocurrency asset held by the user.
  - `id` (Primary Key, UUID)
  - `symbol` (e.g., BTC, ETH)
  - `amount` (BigDecimal)
  - `source` (MANUAL or BINANCE)
  - `user_id` (Foreign Key to User, UUID)

- **UserBinanceKeys**: Stores encrypted API keys for Binance integration.
  - `id` (Primary Key, UUID)
  - `api_key` (Encrypted)
  - `api_secret` (Encrypted)
  - `user_id` (Foreign Key to User, UUID)

- **BudgetTemplate**: A recurring budget definition that auto-instantiates budgets on a schedule.
  - `id` (Primary Key, UUID)
  - `budget_limit` (BigDecimal)
  - `recurrence_type` (`MONTHLY`, `QUARTERLY`, `YEARLY`)
  - `active` (default `true`)
  - `category_id` (Foreign Key to Category, UUID)
  - `user_id` (Foreign Key to User, UUID)
  - `created_at`

- **BudgetAlert**: A threshold-based alert for budget spending.
  - `id` (Primary Key, UUID)
  - `threshold_percentage` (1–100)
  - `active` (default `true`)
  - `last_notified_at`
  - `budget_id` (Foreign Key to Budget, UUID)
  - `user_id` (Foreign Key to User, UUID)
  - `created_at`

- **AuditLog**: Immutable record of every write operation on core entities.
  - `id` (Primary Key, UUID)
  - `user_id` (UUID of the actor)
  - `action` (e.g. `CREATE_TRANSACTION`, `DELETE_ACCOUNT`)
  - `entity_type` (e.g. `Transaction`, `Account`)
  - `entity_id` (ID of the affected entity)
  - `new_value` (JSON snapshot of response DTO, nullable)
  - `timestamp`
  - `ip_address` (nullable)

- **ApiKey**: Machine-to-machine API keys for accessing the API without JWT.
  - `id` (Primary Key, UUID)
  - `name`
  - `key_hash` (SHA-256 of the plaintext key — never stored plain)
  - `scopes` (comma-separated, e.g. `READ_ALL,WRITE_TRANSACTIONS`)
  - `expires_at` (nullable)
  - `last_used_at` (nullable)
  - `active` (default `true`)
  - `user_id` (Foreign Key to User, UUID)
  - `created_at`

## Getting Started

To get a local copy up and running, follow these simple steps.

### Prerequisites

- JDK 25 or newer
- Maven
- Docker and Docker Compose (optional, for containerized deployment)
- MongoDB instance (for semantic caching)

### 1. Clone the repository

```shell
git clone <your-repository-url>
cd nexaBudget-be
```

### 2. Running the Application

You can run the application either locally using Maven or with Docker.

#### Running Locally with Maven

This method is ideal for development and debugging.

##### 1. Database Setup

You need a running instance of PostgreSQL. Set the following environment variables or update `src/main/resources/application.properties`:

```properties
# Database Configuration
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/nexabudget}
spring.datasource.username=nexabudget-be
spring.datasource.password=${DB_PWD:your_password}
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA Configuration
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.open-in-view=false

# JWT Configuration
app.jwtSecret=${JWT_SECRET:tua-chiave-segreta-molto-lunga-e-sicura-di-almeno-64-caratteri}
app.jwtExpirationInMs=86400000

# Crypto Encryption (for API Keys)
crypto.encryption.key=${CRYPTO_ENCRYPTION_KEY:MySuperSecretKey1234567890123456}

# GoCardless Integration
gocardless.integrator.baseUrl=http://localhost:3000

# Google Gemini AI Configuration
spring.ai.google.genai.api-key=${GEMINI_API_KEY:your_gemini_api_key}
spring.ai.google.genai.chat.options.model=gemini-2.5-flash-lite
spring.ai.google.genai.chat.options.temperature=0.1

# Semantic Cache / Vector Store (MongoDB)
spring.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/nexabudget-be}
spring.ai.vectorstore.mongodb.collection-name=semantic_cache
spring.ai.vectorstore.mongodb.index-name=semantic_cache_index
spring.ai.vectorstore.mongodb.initialize-schema=false

# Redis/Valkey Cache Configuration
spring.cache.type=redis
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.username=${REDIS_USERNAME:}
spring.data.redis.database=0
spring.data.redis.ssl.enabled=${REDIS_SSL_ENABLED:false}

# Actuator and Monitoring
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
```

**Required Environment Variables:**

- `DB_URL`: PostgreSQL database URL
- `DB_PWD`: PostgreSQL database password
- `JWT_SECRET`: Secret key for JWT signing — **must be set and must not equal the dev default**; the application refuses to start otherwise (min 32 chars)
- `GEMINI_API_KEY`: Google Gemini API key
- `MONGODB_URI`: MongoDB connection URI (for vector store)
- `CRYPTO_ENCRYPTION_KEY`: 32-char key for encrypting sensitive data (Binance keys)

**Optional Environment Variables:**

- `REDIS_HOST`: Redis/Valkey host (default: localhost)
- `REDIS_PORT`: Redis/Valkey port (default: 6379)
- `REDIS_USERNAME`: Redis/Valkey username
- `REDIS_PASSWORD`: Redis/Valkey password
- `REDIS_SSL_ENABLED`: Enable SSL for Redis connection (default: false)

##### 2. Build the project

Use Maven to build the project and download dependencies.

```shell
./mvnw clean install
```

##### 3. Run the application

You can run the application using the Spring Boot Maven plugin.

```shell
./mvnw spring-boot:run
```

The application will start on <http://localhost:8080>.

#### Running with Docker

This is the recommended way to run the application in a production-like environment. The following commands will start
the application, PostgreSQL database, Valkey cache, and MongoDB.

##### 1. Run the JVM-based image

This command builds the JVM image and starts all services (app, PostgreSQL, Valkey, MongoDB) in detached mode.

```shell
docker-compose up --build -d
```

##### 2. Run the Native image

For better performance and a smaller memory footprint, you can run the native-compiled version.

```shell
docker-compose -f docker-compose.native.yml up --build -d
```

In both cases, the application will be available at <http://localhost:8080>, PostgreSQL at port 5432, Valkey at port
6379, and MongoDB at port 27017.

To stop and remove the containers, use:

```shell
# For JVM
docker-compose down

# For Native
docker-compose -f docker-compose.native.yml down
```

## API Documentation

Once the application is running, you can access the Swagger UI documentation at:

- **Swagger UI**: <http://localhost:8080/swagger-ui.html>
- **OpenAPI JSON**: <http://localhost:8080/v3/api-docs>

The API provides the following endpoints:

### Authentication (Public)

- `POST /api/auth/login` - User login
- `POST /api/auth/register` - User registration

### Users (Protected)

- `GET /api/users/me` - Get current user profile
- `PUT /api/users/me` - Update current user profile

### Accounts (Protected)

- `GET /api/accounts` - Get all user accounts
- `POST /api/accounts` - Create new account
- `GET /api/accounts/{id}` - Get account details
- `PUT /api/accounts/{id}` - Update account
- `DELETE /api/accounts/{id}` - Delete account

### Transactions (Protected)

- `GET /api/transactions` - Get all user transactions (full list)
- `GET /api/transactions/paged?page=0&size=20` - Get transactions with pagination, sorted by date descending
- `POST /api/transactions` - Create new transaction
- `GET /api/transactions/{id}` - Get transaction details
- `PUT /api/transactions/{id}` - Update transaction
- `DELETE /api/transactions/{id}` - Delete transaction
- `GET /api/transactions/daterange?start=&end=` - Transactions in a date range
- `GET /api/transactions/account/{id}/daterange` - Account transactions in a date range

### Categories (Protected)

- `GET /api/categories` - Get all categories (default + user custom)
- `POST /api/categories` - Create custom category — returns `409 Conflict` if a category with the same name and type already exists for the user
- `PUT /api/categories/{id}` - Update category
- `DELETE /api/categories/{id}` - Delete category
- `POST /api/categories/{sourceId}/merge-into/{targetId}` - Merge source into target (moves all transactions and budgets, deletes source) — returns `400` if the two categories have different transaction types

### Budgets (Protected)

- `GET /api/budgets/` - Get all user budgets
- `POST /api/budgets` - Create new budget — `endDate` must be ≥ `startDate` (returns `400` otherwise)
- `GET /api/budgets/active` - Active budgets at a given date
- `GET /api/budgets/usage` - Spending percentage for active budgets
- `GET /api/budgets/remaining` - Remaining budget for active budgets
- `PUT /api/budgets/{id}` - Update budget (ownership verified — returns `404` if budget belongs to another user)
- `DELETE /api/budgets/{id}` - Delete budget (ownership verified — returns `404` if budget belongs to another user)

### GoCardless Integration (Protected)

- `GET /api/gocardless/bank` - List supported banks by country
- `POST /api/gocardless/bank/link` - Generate bank link — returns `503` if GoCardless is unavailable
- `GET /api/gocardless/bank/{localAccountId}/account` - List bank accounts linked via GoCardless
- `POST /api/gocardless/bank/{localAccountId}/link` - Link a bank account to a local account
- `POST /api/gocardless/bank/{localAccountId}/sync` - Start async transaction sync (uses atomic DB lock to prevent concurrent syncs)

### Crypto Portfolio (Protected)

- `GET /api/crypto/portfolio` - Get crypto portfolio value (supports currency conversion)
- `POST /api/crypto/holdings` - Add/Update manual crypto holding
- `POST /api/crypto/binance/keys` - Save Binance API keys (encrypted)
- `POST /api/crypto/binance/sync` - Trigger sync from Binance

### Trash / Recovery (Protected)

Deleted accounts and transactions are soft-deleted and retained for 30 days before permanent purge (runs daily at 3 AM).

- `GET /api/trash/transactions` - List soft-deleted transactions
- `POST /api/trash/transactions/{id}/restore` - Restore a soft-deleted transaction
- `GET /api/trash/accounts` - List soft-deleted accounts
- `POST /api/trash/accounts/{id}/restore` - Restore a soft-deleted account (also restores all its transactions)

### Financial Reports (Protected)

- `GET /api/reports/monthly-trend?months=12` - Monthly income/expense totals for the last N months
- `GET /api/reports/category-breakdown?type=OUT&startDate=&endDate=` - Spending/income by category in a date range
- `GET /api/reports/month-comparison?year=&month=` - Compare a month vs. the previous month
- `GET /api/reports/monthly-projection` - Projected end-of-month totals based on current spending rate
- `POST /api/reports/ai-analysis` - Start an asynchronous AI financial analysis job for a given date range (returns `jobId`)
- `GET /api/reports/ai-analysis/{jobId}` - Check the status and retrieve the result of an AI analysis job

### Budget Templates (Protected)

Templates automatically instantiate budgets on the 1st of every month (MONTHLY), at the start of each quarter (QUARTERLY), and on January 1st (YEARLY).

- `GET /api/budget-templates` - List all budget templates
- `POST /api/budget-templates` - Create a budget template
- `GET /api/budget-templates/{id}` - Get a budget template
- `PUT /api/budget-templates/{id}` - Update a budget template
- `DELETE /api/budget-templates/{id}` - Delete a budget template

### Budget Alerts (Protected)

Alerts are evaluated hourly. A notification is logged when `spent / limit ≥ thresholdPercentage` and the alert has not been triggered in the past 24 hours.

- `GET /api/budget-alerts` - List all budget alerts
- `POST /api/budget-alerts` - Create a budget alert (`thresholdPercentage`: 1–100)
- `GET /api/budget-alerts/{id}` - Get a budget alert
- `PUT /api/budget-alerts/{id}` - Update a budget alert
- `DELETE /api/budget-alerts/{id}` - Delete a budget alert

### Audit Log (Protected)

Every write operation on core entities (Transaction, Account, Budget, Category) is automatically recorded.

- `GET /api/audit-log?page=0&size=20` - Paginated audit log for the current user
- `GET /api/audit-log/{entityType}/{entityId}` - History of a specific entity

### API Keys (Protected)

Machine-to-machine access without JWT. The plaintext key is shown **only once** at creation.

- `POST /api/api-keys` - Create a new API key (returns plaintext key once)
- `GET /api/api-keys` - List all API keys (no plaintext values)
- `PUT /api/api-keys/{id}` - Update name, scopes, expiry, or active status
- `DELETE /api/api-keys/{id}` - Delete a key permanently

### Import Transactions (Protected)

Two-step import: preview first, then confirm.

- `POST /api/accounts/{id}/import/csv/preview` - Preview CSV import (`multipart/form-data`: `file` + `mapping`)
- `POST /api/accounts/{id}/import/csv` - Import from CSV
- `POST /api/accounts/{id}/import/ofx/preview` - Preview OFX/QFX import (`multipart/form-data`: `file`)
- `POST /api/accounts/{id}/import/ofx` - Import from OFX/QFX

All protected endpoints require a valid JWT token **or** an API key:

```http
Authorization: Bearer <your_jwt_token>
# or
X-Api-Key: <your_api_key>
```

## Features

### JWT Authentication

The application uses JWT (JSON Web Tokens) for secure authentication. Tokens expire after 24 hours by default (configurable via `app.jwtExpirationInMs` property).

The application validates the `JWT_SECRET` at startup — it will **refuse to start** if the secret is the development default or shorter than 32 characters.

### Security & Validation Rules

- **Password updates** are always BCrypt-encoded through `UserService.updateUserProfile()` — raw passwords are never persisted.
- **Budget ownership** is enforced on `PUT /api/budgets/{id}` and `DELETE /api/budgets/{id}` — a user can only modify their own budgets.
- **Category uniqueness**: a user cannot have two categories with the same name and transaction type (`UNIQUE(user_id, name, transaction_type)`).
- **Budget date validation**: `endDate`, when provided, must be ≥ `startDate`. The API returns `400 Bad Request` otherwise.
- **GoCardless sync**: concurrent sync attempts on the same account are prevented with an atomic DB-level lock (`UPDATE … WHERE isSynchronizing = false`).
- **GoCardless token generation**: returns `503 Service Unavailable` (instead of a `NullPointerException`) when GoCardless does not return a valid response.
- **Category merge**: `POST /categories/{sourceId}/merge-into/{targetId}` moves all transactions and budgets from source to target atomically via bulk JPQL UPDATE, then deletes the source. Only user-owned categories can be used as source (default categories are protected).

### Resilience & Performance

- **Automatic retry** on transient network failures: GoCardless API calls and exchange rate lookups retry up to 3 times with exponential backoff (1 s, 2 s) via Spring Retry `@Retryable`. Empty fallback results are never cached (via `unless` conditions), so subsequent requests retry properly.
- **HTTP timeouts**: GoCardless RestClient: 5 s connect / 10 s read. Binance and ExchangeRate RestClients: 5 s / 5 s.
- **Batch crypto prices**: `CryptoPortfolioService.getPortfolioValue()` fetches all USDT prices in a single `GET /api/v3/ticker/price` call instead of one call per symbol. Falls back to per-symbol lookup for any symbol not present in the batch response.
- **Cache warming**: at startup, `CacheWarmupRunner` pre-populates the exchange rate cache (USD → EUR, USD → GBP) asynchronously so the first real request hits a warm cache.

### AI-Powered Transaction Categorization

Transactions can be automatically categorized using Google's Gemini AI. The AI analyzes transaction descriptions and suggests the most appropriate category based on your existing categories.

### GoCardless Integration

Connect your real bank accounts through GoCardless (formerly Nordigen) to automatically import transactions. The integration supports:

- Creating bank connection requisitions
- Linking external accounts
- Syncing transactions from linked accounts

### Crypto Portfolio Management

Track your cryptocurrency assets in one place:

- **Binance Integration**: securely connect your Binance account to auto-sync holdings.
- **Manual Tracking**: add assets from other wallets or exchanges manually.
- **Portfolio Valuation**: view your total portfolio balance in your preferred currency.

### Soft Delete & Trash Recovery

Deleting a transaction or account performs a **soft delete** — the record is marked `deleted = true` with a timestamp instead of being removed from the database. All normal JPA queries automatically filter out deleted rows via Hibernate's `@SQLRestriction("deleted = false")`.

- Deleted items are accessible via the `/api/trash/` endpoints for 30 days.
- Restoring an account also restores all of its soft-deleted transactions.
- A scheduled job at **3 AM daily** permanently purges items older than 30 days.

### Financial Reports

Aggregate reports built directly on the transaction data:

- **Monthly trend**: totals per month for income and expenses.
- **Category breakdown**: ranked spending/income by category for any date range.
- **Month comparison**: compare totals for any month vs. the previous one.
- **Monthly projection**: extrapolates end-of-month totals from the current daily spending rate.
- **AI Asynchronous Analysis**: users can generate a deep analysis report of their transactions over a requested period using the `AiReportService`. The transactions are formatted as a CSV file and passed to Google Gemini AI as a multipart file attachment (Spring AI Media) instead of plain text, avoiding context prompt bloating. The endpoint returns a pending job ID that can be polled for the final markdown report.

### Budget Templates

Define recurring budget templates (`MONTHLY`, `QUARTERLY`, `YEARLY`) that automatically instantiate real budgets on a schedule:

- Monthly templates fire on the **1st of every month**.
- Quarterly templates fire on the **1st of January, April, July, October**.
- Yearly templates fire on **January 1st**.

### Budget Alerts

Set percentage-based thresholds on any budget. An hourly scheduler checks all active alerts and logs a warning when `spent / limit ≥ thresholdPercentage`. Alerts suppress repeated notifications within a **24-hour cooldown window**.

### Multi-Currency Transfers

When a transfer is created between two accounts with different currencies, the application automatically fetches the current exchange rate via `ExchangeRateService` and converts the destination amount. The IN-leg transaction stores `exchangeRate`, `originalCurrency`, and `originalAmount` for full auditability.

### Audit Log

Every write operation (create, update, delete) on Transactions, Accounts, Budgets, and Categories is automatically recorded via a Spring AOP aspect (`AuditAspect`) — no changes to service code required. Each entry captures the user ID, action name, affected entity type/ID, serialized response DTO, timestamp, and client IP address.

### API Key Management

Users can generate API keys for machine-to-machine access (e.g. scripts, dashboards, automations). Keys are:
- Generated with a cryptographically secure random 32-byte value (base64url-encoded)
- Stored only as a SHA-256 hash — the plaintext is shown exactly once at creation
- Validated on every request via `ApiKeyAuthenticationFilter` (reads `X-Api-Key` header)
- Configurable with scopes, expiry dates, and active/inactive status

### Import Transactions (CSV / OFX)

Import transactions from bank exports in CSV or OFX format:

- **CSV**: flexible column mapping (date column, amount column, description column, optional type column, date format, delimiter)
- **OFX**: supports both OFX 1.x SGML and OFX 2.x XML formats
- **Two-step flow**: call `/preview` first to see which rows are duplicates, then `/import` to confirm
- **Deduplication**: SHA-256 hash of `(accountId | date | amount | description)` stored in `import_hash`; OFX FITID tracked via `external_id`
- **AI auto-categorization**: each imported transaction is automatically categorized via Google Gemini

### Semantic Caching (MongoDB Vector Store)

Uses **Spring AI** with **MongoDB Atlas** as a vector store to semantically cache AI responses, reducing costs and latency for repeated or similar queries.

### Caching with Redisson & Valkey/Redis

The application uses **Redisson**, an advanced Redis client, to connect to Valkey (a Redis-compatible cache) to improve
performance and reduce external API calls.

**Why Redisson?**

- 🚀 Superior performance with optimized connection pooling
- 🔧 Advanced features: distributed locks, collections, pub/sub
- 🎯 Better error handling with automatic retries and failover
- 📦 Native JSON codec using Jackson

**Configuration Approach:**

- ✅ Programmatic configuration via `RedissonConfig.java`
- ✅ Type-safe and compile-time verified
- ✅ Automatic SSL support for production
- ✅ No external YAML files needed

#### Cached Methods (6-hour TTL)

- **`getBankAccounts`**: Caches bank account lists from GoCardless
  - Cache key: `requisitionId`
  - Duration: 6 hours
- **`getGoCardlessTransaction`**: Caches transactions from GoCardless
  - Cache key: `requisitionId_accountId`
  - Duration: 6 hours

#### Benefits

- 🚀 Faster response times for repeated queries
- 💰 Reduced API calls to external services
- ⚡ Better scalability under load
- 🔄 Automatic retry and reconnection handling

For more details, see [CACHE.md](./CACHE.md).

### Monitoring and Health Checks

The application exposes Spring Boot Actuator endpoints for monitoring:

- **Health**: <http://localhost:8080/actuator/health>
- **Metrics**: <http://localhost:8080/actuator/metrics>
- **Prometheus**: <http://localhost:8080/actuator/prometheus>

### GraalVM Native Image

The application supports compilation to a native executable using GraalVM, providing:

- Faster startup time
- Lower memory footprint
- Better resource efficiency

## Environment Variables

| Variable                        | Description                                 | Required | Default                                                        |
|---------------------------------|---------------------------------------------|----------|----------------------------------------------------------------|
| `DB_URL`                        | PostgreSQL database URL                     | Yes      | jdbc:postgresql://localhost:5432/nexabudget                    |
| `DB_PWD`                        | PostgreSQL password                         | Yes      | -                                                              |
| `GEMINI_API_KEY`                | Google Gemini API key                       | Yes      | -                                                              |
| `MONGODB_URI`                   | MongoDB Connection URI                      | Yes      | mongodb://localhost:27017/nexabudget-be                        |
| `CRYPTO_ENCRYPTION_KEY`         | Key for encrypting API keys (32 chars)      | Yes      | -                                                              |
| `REDIS_HOST`                    | Redis/Valkey host for caching               | No       | localhost                                                      |
| `REDIS_PORT`                    | Redis/Valkey port                           | No       | 6379                                                           |
| `REDIS_USERNAME`                | Redis/Valkey username                       | No       | (empty)                                                        |
| `REDIS_PASSWORD`                | Redis/Valkey password                       | No       | (empty)                                                        |
| `REDIS_SSL_ENABLED`             | Enable SSL for Redis                        | No       | false                                                          |
| `JWT_SECRET`                    | JWT signing secret (min 32 chars, must not equal dev default) | **Yes** | — (app fails to start without it) |
| `app.jwtExpirationInMs`         | JWT token expiration time in milliseconds   | No       | 86400000 (24 hours)                                            |
| `gocardless.integrator.baseUrl` | GoCardless integrator service URL           | No       | <http://localhost:3000>                                          |

## Development

### Project Structure

```text
src/main/java/it/iacovelli/nexabudgetbe/
├── config/          # Configuration classes
├── controller/      # REST controllers
├── dto/            # Data Transfer Objects
├── model/          # JPA entities
├── repository/     # Spring Data repositories
├── security/       # JWT security components
└── service/        # Business logic services
```

### Building Native Image

To build a native executable locally:

```shell
./mvnw -Pnative clean package
```

Note: This requires GraalVM to be installed and configured.
