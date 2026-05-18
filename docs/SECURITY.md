# Security Architecture

## Overview

Security in NexaBudget Backend is designed to handle both user sessions (via Web/Mobile apps) and machine-to-machine (M2M) communication. It leverages **Spring Security** with state-of-the-art configurations.

## Authentication Mechanisms

Two filters run in front of the Spring Security chain:

1. `ApiKeyAuthenticationFilter` (executes **first**, so an `X-Api-Key` header short-circuits JWT validation)
2. `JwtAuthenticationFilter`

Either mechanism produces an authenticated `SecurityContext` — the rest of the application is agnostic to which one populated it.

### 1. JWT (JSON Web Tokens)

* **Usage:** primarily for user authentication from the web/mobile client.
* **Library:** `jjwt 0.13` (api/impl/jackson).
* **Mechanism:**
  * Users authenticate via `POST /api/auth/login`.
  * The server issues a stateless JWT signed with HMAC SHA-256 using `JWT_SECRET` (≥ 32 chars; the application **refuses to start** if the secret equals the dev default).
  * The token carries the user's UUID as subject.
  * Default expiration: **24 hours** (`app.jwtExpirationInMs=86400000`).
* **Validation:** `JwtAuthenticationFilter` parses the `Authorization: Bearer <token>` header, delegates verification to `JwtTokenProvider`, loads the user via `UserDetailsServiceImpl`, and populates `SecurityContextHolder`.

### 2. API Keys

* **Usage:** external scripts, automation, third-party integrations (M2M).
* **Endpoint:** `POST/GET/PUT/DELETE /api/api-keys` (note: dashed path).
* **Header:** `X-Api-Key: <plaintext-key>`.
* **Mechanism:**
  * On creation, a random key is generated and returned **once** — never stored in plaintext.
  * The database (`api_keys` table) holds only the SHA-256 hash (`key_hash`, indexed and unique).
  * Each request: the filter hashes the inbound header, looks the row up, then validates `active = true` and `expires_at IS NULL OR expires_at > now()`. On success it updates `last_used_at`.
* **Lifecycle:** an `ApiKey` can be deactivated (`active = false`), given an expiry, or hard-deleted by the owning user.

## Authorization & Ownership

* **Endpoint Security:** all `/api/**` endpoints require authentication, except `/api/auth/**` (login/registration) and public webhooks. Swagger UI is enabled in dev (`springdoc.swagger-ui.enabled=true`) and disabled via the prod profile.
* **Entity Ownership:** every service method that touches user-scoped data refuses cross-tenant access. Representative checks:
  * `BudgetController.updateBudget` / `deleteBudget` use `getBudgetByIdAndUser()` — only the owner can mutate.
  * `CategoryService.mergeCategories(sourceId, targetId, user)` refuses to merge a default (non-user-owned) category as source.
  * `Transaction`/`Account` queries are filtered both by `user_id` and the soft-delete `@SQLRestriction`.
* **Password updates** must always go through `UserService.updateUserProfile()` — direct mutation of `passwordHash` would bypass BCrypt.

## Cryptography & Secrets

* **Passwords:** stored as `BCryptPasswordEncoder` hashes. Plaintext is never logged.
* **External integration keys** (Binance API/secret, Coinbase API-key-name/private-key) are encrypted at rest by a JPA `AttributeConverter` (`CryptoConverter`) using AES with `CRYPTO_ENCRYPTION_KEY` (32+ chars). They are decrypted only in-memory when the integration runs.
* **API keys** are SHA-256 hashed (one-way) before storage.
* **Environment variables:** all secrets — `DB_PWD`, `JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`, `GEMINI_API_KEY`, `MONGODB_URI`, `REDIS_PASSWORD`, `SMTP_PWD` — are injected at runtime. None are hardcoded.
* **Coinbase keys (EC):** Bouncy Castle is registered at startup to support EC private keys used by the Coinbase Advanced Trade SDK.

## Rate Limiting

* **Implementation:** `RateLimitingFilter` uses **Bucket4j** with a per-client-IP token bucket (`ConcurrentHashMap<String, Bucket>`).
* **Scope:** authentication endpoints (`/api/auth/**`) — the highest-risk surface for brute-force.
* **Configuration:**
  * `security.rate-limit.enabled` (default `true`)
  * `security.rate-limit.requests-per-minute` (default `10`)

## Auditing

`AuditAspect` (`@Aspect`, in `config/`) is an `@AfterReturning` advice that intercepts service write methods and persists an `audit_logs` row via `AuditLogService`. The acting user is resolved from `SecurityContextHolder`, the client IP from `RequestContextHolder`. Read endpoints: `GET /api/audit-log?page=&size=`, `GET /api/audit-log/{entityType}/{entityId}`.
