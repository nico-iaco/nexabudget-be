# Data Model and Database Schema

## Overview

NexaBudget uses **PostgreSQL** as its primary relational data store. The schema is generated from JPA entities (Hibernate) with UUID primary keys. In production `spring.jpa.hibernate.ddl-auto=validate` is enforced — every schema change must ship as an explicit DB migration script (see CLAUDE.md "New DB columns" sections).

### Key Design Principles

* **UUIDs everywhere:** All entities use `UUID` for primary keys (`@GeneratedValue(strategy = GenerationType.UUID)`) — prevents enumeration attacks.
* **Soft deletion:** `Account` and `Transaction` carry `deleted BOOLEAN` + `deleted_at TIMESTAMP`. The entity classes are annotated with `@SQLRestriction("deleted = false")` so soft-deleted rows are invisible to ordinary queries. Hard deletion is reserved for the `TrashService` purge job (>30 days) and for test teardown helpers (`*Repository.hardDeleteAll()`).
* **Net category accounting:** `Category` has **no** `transactionType` column. Budget spend on a category is computed as `OUT − IN` over the period (`TransactionRepository.sumNetByUserAndCategoryAndDateRange()`). The unique constraint on `categories` is `(user_id, name)`.
* **Auditing:** `AuditAspect` writes one `audit_logs` row per intercepted service write (user resolved from `SecurityContextHolder`, IP from `RequestContextHolder`).
* **Multi-currency:** `transactions.exchange_rate`, `original_currency`, `original_amount` capture the FX conversion applied when source/destination accounts differ in currency.
* **Import dedup:** `transactions.import_hash` stores SHA-256 of `(accountId|date|amount|description)`; combined with `external_id` (FITID) it prevents duplicate ingestion of CSV/OFX rows.
* **Indexes:** `transactions(user_id, transaction_date)`, `transactions(account_id, transaction_date)`, `transactions(category_id)`, `budgets(user_id, start_date, end_date)`, `api_keys(key_hash)`, `api_keys(user_id)`.

## Entity Relationship Diagram

```mermaid
erDiagram
    USER ||--o{ ACCOUNT : owns
    USER ||--o{ BUDGET : creates
    USER ||--o{ BUDGET_TEMPLATE : creates
    USER ||--o{ CATEGORY : defines
    USER ||--o{ CHAT_SESSION : owns
    USER ||--o{ API_KEY : generates
    USER ||--o{ AUDIT_LOG : produces
    USER ||--o{ CRYPTO_HOLDING : holds
    USER ||--o| USER_BINANCE_KEYS : configures
    USER ||--o| USER_COINBASE_KEYS : configures

    ACCOUNT ||--o{ TRANSACTION : contains
    CATEGORY ||--o{ TRANSACTION : categorizes
    CATEGORY ||--o{ BUDGET : allocated_in
    CATEGORY ||--o{ BUDGET_TEMPLATE : allocated_in

    BUDGET ||--o{ BUDGET_ALERT : monitored_by
    CHAT_SESSION ||--o{ CHAT_MESSAGE : contains

    USER {
        uuid id PK
        string username UK
        string email UK
        string password_hash
        string default_currency "ISO 4217, default EUR"
        timestamp created_at
        timestamp updated_at
    }

    ACCOUNT {
        uuid id PK
        uuid user_id FK
        string name
        enum type "CONTO_CORRENTE|RISPARMIO|INVESTIMENTO|CONTANTI"
        string currency
        string requisition_id "GoCardless"
        string external_account_id "GoCardless"
        timestamp last_external_sync
        boolean is_synchronizing "atomic sync lock"
        boolean deleted
        timestamp deleted_at
        timestamp created_at
    }

    TRANSACTION {
        uuid id PK
        uuid user_id FK
        uuid account_id FK
        uuid category_id FK "nullable"
        decimal amount "19,4"
        enum type "IN|OUT"
        string description
        date transaction_date
        string note
        string transfer_id "links the two sides of a transfer"
        string external_id "FITID / bank ref"
        decimal exchange_rate "20,8 nullable"
        string original_currency "ISO 4217, nullable"
        decimal original_amount "19,4 nullable"
        string import_hash "SHA-256, 64 chars"
        boolean deleted
        timestamp deleted_at
        timestamp created_at
    }

    CATEGORY {
        uuid id PK
        uuid user_id FK
        string name
        string color
        string icon
    }

    BUDGET {
        uuid id PK
        uuid user_id FK
        uuid category_id FK
        decimal limit_amount
        date start_date
        date end_date
    }

    BUDGET_TEMPLATE {
        uuid id PK
        uuid user_id FK
        uuid category_id FK
        decimal limit_amount
        enum recurrence "MONTHLY|QUARTERLY|YEARLY"
        boolean active
    }

    BUDGET_ALERT {
        uuid id PK
        uuid budget_id FK
        int threshold_percentage "1-100"
        timestamp last_notified_at
        boolean active
    }

    CRYPTO_HOLDING {
        uuid id PK
        uuid user_id FK
        string symbol
        decimal amount "28,18"
        enum source "MANUAL|BINANCE|COINBASE"
    }

    USER_BINANCE_KEYS {
        uuid id PK
        uuid user_id FK
        string api_key "AES via CryptoConverter"
        string secret_key "AES via CryptoConverter"
    }

    USER_COINBASE_KEYS {
        uuid id PK
        uuid user_id FK
        string api_key_name "AES via CryptoConverter"
        string private_key "AES via CryptoConverter"
    }

    API_KEY {
        uuid id PK
        uuid user_id FK
        string name
        string key_hash "SHA-256, unique"
        string scopes
        timestamp expires_at
        timestamp last_used_at
        boolean active
        timestamp created_at
    }

    AUDIT_LOG {
        uuid id PK
        uuid user_id FK
        string entity_type
        string entity_id
        string action "CREATE|UPDATE|DELETE"
        string ip_address
        text changes_json
        timestamp created_at
    }

    CHAT_SESSION {
        uuid id PK
        uuid user_id FK
        string title
        timestamp created_at
    }

    CHAT_MESSAGE {
        uuid id PK
        uuid session_id FK
        string role "USER|ASSISTANT|TOOL"
        text content
        timestamp created_at
    }
```

> Note: column names in the diagram reflect the JPA `@Column(name = …)` mapping; some Java fields use camelCase (e.g. `limitAmount`, `lastNotifiedAt`).

## Enumerations

| Enum | Values |
| :--- | :--- |
| `AccountType` | `CONTO_CORRENTE`, `RISPARMIO`, `INVESTIMENTO`, `CONTANTI` |
| `TransactionType` | `IN`, `OUT` (signed convention: net = OUT − IN) |
| `HoldingSource` | `MANUAL`, `BINANCE`, `COINBASE` |
| `RecurrenceType` | `MONTHLY`, `QUARTERLY`, `YEARLY` |

## Vector Store (MongoDB Atlas)

MongoDB Atlas hosts the **semantic cache** for AI calls, configured via `spring.ai.vectorstore.mongodb.*`.

* **Collection:** `semantic_cache` (configurable via `SEMANTIC_CACHE_COLLECTION_NAME`)
* **Atlas Vector Search index:** `semantic_cache_index` (configurable via `SEMANTIC_CACHE_INDEX_NAME`)
* **Embedding model:** `gemini-embedding-001`, dimensionality **3072**
* **Purpose:** before issuing a Gemini call (categorization, chatbot), `SemanticCacheService` performs a similarity search; on hit, the cached completion is returned, cutting cost and latency.
* `spring.ai.vectorstore.mongodb.initialize-schema=false` — the index must be created out-of-band in Atlas.

## Manual Migration Notes

Because DDL mode is `validate`, the following schema changes must be applied manually in production (history captured in CLAUDE.md):

* **Phase 4** — add `transactions.deleted`, `transactions.deleted_at`, `accounts.deleted`, `accounts.deleted_at`; create `budget_templates`, `budget_alerts`.
* **Phase 5** — add `transactions.exchange_rate`, `original_currency`, `original_amount`, `import_hash`; create `audit_logs`, `api_keys`.
* **Net category accounting** — deduplicate `(user_id, name)` rows in `categories`, remap dependent `transactions.category_id` / `budgets.category_id`, then `DROP CONSTRAINT uk_category_user_name_type`, `DROP COLUMN transaction_type`, `ADD CONSTRAINT uk_category_user_name UNIQUE (user_id, name)`.
