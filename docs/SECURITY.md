# Security Architecture

## Overview

Security in NexaBudget Backend is designed to handle both user sessions (via Web/Mobile apps) and machine-to-machine (M2M) communication. It leverages **Spring Security** with state-of-the-art configurations.

## Authentication Mechanisms

### 1. JWT (JSON Web Tokens)

* **Usage:** Primarily for user authentication from the frontend client.
* **Mechanism:**
  * Users authenticate via `/api/auth/login`.
  * The server issues a stateless JWT signed with HMAC SHA-256 (`JWT_SECRET`).
  * The token contains the user's UUID and roles.
  * Default expiration is 24 hours.
* **Validation:** Handled by `JwtAuthenticationFilter`. Each request is intercepted, the Authorization header (`Bearer <token>`) is parsed, and the `SecurityContextHolder` is populated.

### 2. API Keys

* **Usage:** For external scripts, automation, or third-party integrations (M2M).
* **Mechanism:**
  * Users can generate API Keys via `/api/apikeys`.
  * Keys are generated as random UUID strings.
  * **Storage:** The raw key is *never* stored. It is hashed using SHA-256 before being saved to the database.
  * The user is shown the raw key only once upon creation.
* **Validation:** Handled by `ApiKeyAuthenticationFilter`. If an `X-API-KEY` header is present, the filter hashes the incoming key and compares it against the database. If valid, the user associated with the key is authenticated.

## Authorization & Ownership

* **Endpoint Security:** All `/api/**` endpoints (except `/api/auth/**` and public webhooks) require an authenticated user.
* **Entity Ownership:** Every service method explicitly checks ownership. For example, `budgetRepository.findByIdAndUser(id, currentUser)` ensures users can only access or modify their own data.

## Cryptography & Secrets

* **Passwords:** User passwords are encrypted using `BCryptPasswordEncoder`. The raw password is never logged or stored.
* **External Keys:** Sensitive integrations like Binance and Coinbase API credentials are symmetrically encrypted in the database using `CryptoConverter` (AES algorithm) and the application property `crypto.encryption.key`.
* **Environment Variables:** All secrets (`JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`, DB passwords) are injected via environment variables and are never hardcoded.

## Rate Limiting

* **Mechanism:** implemented via `RateLimitingFilter`.
* **Scope:** By default, specifically targets authentication endpoints (e.g., `/api/auth/login`) to prevent brute-force attacks.
* **Configuration:** Configurable via `security.rate-limit.requests-per-minute` (default: 10 RPM).
