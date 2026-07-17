# Enable Banking Setup Guide

## Overview

**Enable Banking** (https://enablebanking.com) is NexaBudget's second Open Banking/PSD2 bank
aggregation provider, alongside GoCardless. Unlike GoCardless — which is fronted by an external
`gocardless-integrator` microservice that holds the real secrets — Enable Banking's **Cloud API**
(`api.enablebanking.com`) is called **directly** from this Spring Boot application. Authentication
is a per-request JWT (RS256) signed in-app with an RSA private key tied to an application
registered on Enable Banking's control panel — no intermediary service, no shared secret key,
just an `app_id` + private key pair.

This guide covers registering the application, generating the required key material, configuring
environment variables, and validating the integration end-to-end. It assumes familiarity with the
[Architecture Guide](ARCHITECTURE.md) and [API & Features Guide](API_GUIDE.md).

## 1. Register an application on the Enable Banking control panel

1. Sign up / log in at the Enable Banking control panel.
2. Create a new **application**. Choose the environment matching your deployment:
   - **Sandbox** — for development/testing, connects to mock/test ASPSPs only.
   - **Production** — requires a completed onboarding review by Enable Banking before it can reach
     real banks.
3. Note the **Application ID** (`app_id`) shown in the control panel — this maps to
   `ENABLEBANKING_APP_ID`.
4. Register the **redirect URL** for the OAuth-style consent callback (see §4 below) — it must be
   registered **exactly** as the value you will configure in `ENABLEBANKING_REDIRECT_URL`, including
   scheme, host, and path. A mismatch causes Enable Banking to reject the authorization request.
5. When prompted for key material, choose one of the two options in §2 below.

## 2. Generate the RSA key pair

The control panel needs to associate a public key (or certificate) with your `app_id` so it can
verify the JWTs this app signs. You get **two options** during registration — pick whichever fits
your workflow:

### Option A — Let the control panel generate the key in your browser (no OpenSSL needed)

The Enable Banking control panel can generate the RSA key pair **client-side, in your browser**
(via the Web Crypto `SubtleCrypto` API): the private key never leaves your machine or gets
transmitted over the network, and only the resulting public key/certificate is registered against
the new `app_id`.

1. During application registration, pick the **"generate in browser"** option instead of uploading
   a public key.
2. Complete registration. The browser then downloads the generated private key as a `.pem` file
   into your Downloads folder, named after the newly assigned application id (e.g.
   `aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.pem`).
3. **Move that file somewhere safe immediately** — it is not stored by Enable Banking and cannot be
   re-downloaded or recovered if lost; you would have to register a new application/key pair.
4. Use its contents as `ENABLEBANKING_PRIVATE_KEY` (see §3) — check the file still opens with
   `-----BEGIN PRIVATE KEY-----` (PKCS8); if your browser/Enable Banking version exports PKCS1
   (`-----BEGIN RSA PRIVATE KEY-----`) instead, convert it with the `openssl pkcs8 -topk8` step
   from Option B below.

This is the quickest path for a first-time sandbox setup — no local OpenSSL required — but Option B
is preferable for production if you want the key generated (and backed up) through your own
infrastructure/secret manager rather than a one-time browser download.

### Option B — Generate the key yourself with OpenSSL and upload the public key

Generate a 2048-bit RSA key pair locally:

```bash
# Private key (keep this secret — never commit it, never log it)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out enablebanking_private_key.pem

# Convert to PKCS8 (required by this app's parser — PKCS1 "-----BEGIN RSA PRIVATE KEY-----" is NOT supported)
openssl pkcs8 -topk8 -nocrypt -in enablebanking_private_key.pem -out enablebanking_private_key_pkcs8.pem

# Public key / certificate to upload to the control panel
openssl rsa -in enablebanking_private_key.pem -pubout -out enablebanking_public_key.pem
```

During registration, choose the **"upload a public key"** (or certificate) option and provide
`enablebanking_public_key.pem`. Keep `enablebanking_private_key_pkcs8.pem` — its contents become
`ENABLEBANKING_PRIVATE_KEY`.

---

Either way, the same constraint applies:

> **Important:** `EnableBankingService.parsePrivateKey()` only understands **PKCS8** PEM
> (`-----BEGIN PRIVATE KEY-----`). If your private key (from either option above) is PKCS1
> (`-----BEGIN RSA PRIVATE KEY-----`), the `-topk8` conversion in Option B is mandatory — otherwise
> `EnableBankingService` logs `ENABLEBANKING_PRIVATE_KEY non valida` at startup and disables the
> Enable Banking provider (the app itself still boots fine; see §3 and §7).

## 3. Configure environment variables

**Enable Banking is an optional provider.** If `ENABLEBANKING_APP_ID` / `ENABLEBANKING_PRIVATE_KEY`
are left unset (or the key fails to parse), `EnableBankingService` logs a warning and disables
itself at runtime — the application still starts normally and GoCardless is unaffected. Every
`/api/banking/enable-banking/...` endpoint then responds `503 Service Unavailable` instead of
crashing the app. This section only matters once you actually want to enable the feature.

| Variable | Required to enable the feature | Description |
| :--- | :--- | :--- |
| `ENABLEBANKING_APP_ID` | Yes | Application ID from the control panel. Also sent as the JWT `kid` header. |
| `ENABLEBANKING_PRIVATE_KEY` | Yes | Contents of `enablebanking_private_key_pkcs8.pem`, PKCS8 PEM. Literal `\n` in place of real newlines is supported (convenient for secret managers / single-line env vars) — normalized before parsing. |
| `ENABLEBANKING_REDIRECT_URL` | Yes (once the two above are set) | **Absolute** URL of the frontend callback page (see §4). No default — the app will send an invalid request to Enable Banking if this is unset or relative. |
| `ENABLEBANKING_BASE_URL` | No | Default `https://api.enablebanking.com`. Override only if pointed at a different Enable Banking environment. |
| `ENABLEBANKING_CONSENT_VALID_DAYS` | No | Default `90`. How many days the user's consent stays valid before requiring re-authorization. |

Example `.env` snippet (values are illustrative — use your own key):

```env
ENABLEBANKING_APP_ID=8f3e2c10-....-....-....-............
ENABLEBANKING_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBg...\n-----END PRIVATE KEY-----\n
ENABLEBANKING_REDIRECT_URL=https://app.nexabudget.it/banking/enable-banking/callback
ENABLEBANKING_BASE_URL=https://api.enablebanking.com
ENABLEBANKING_CONSENT_VALID_DAYS=90
```

For local development against sandbox, use an absolute URL your browser can actually reach, e.g.
`http://localhost:5173/banking/enable-banking/callback`.

## 4. The callback route is a single, static, frontend-owned page

Unlike a typical per-request redirect, **`ENABLEBANKING_REDIRECT_URL` is one fixed URL for the
whole application** — it is not generated per link attempt. It is passed identically on every
`POST /api/banking/enable-banking/link` call
(`EnableBankingAggregationProvider.startLink()` → `EnableBankingService.startAuthorization()`), and
it must be the exact URL registered in the control panel in step 1.

Because the route is shared across every account-linking attempt, `state` carries the
`localAccountId` that initiated the flow, so the frontend page can tell them apart:

```java
// EnableBankingAggregationProvider.startLink()
String state = localAccountId.toString();
```

The frontend must implement **one dedicated page** at that URL which:

1. Reads `code` and `state` from the query string Enable Banking appends on redirect.
2. Calls `POST /api/banking/enable-banking/{state}/session` with body `{ "code": "<code>" }`
   (`state` **is** the `localAccountId`).
3. Presents the returned accounts to the user and completes the link via
   `POST /api/banking/enable-banking/{localAccountId}/link`.

See [API_GUIDE.md](API_GUIDE.md#bank-aggregation-gocardless--enable-banking) for the full endpoint
reference and the two-step vs. one-step flow difference from GoCardless.

## 5. Authentication model in this app

`EnableBankingService.currentToken()` builds and caches a JWT until shortly before its 1-hour TTL
expires:

```java
Jwts.builder()
    .header().keyId(appId).and()
    .issuer("enablebanking.com")
    .audience().single("api.enablebanking.com")
    .issuedAt(issuedAt)
    .expiration(expiration)
    .signWith(privateKey, Jwts.SIG.RS256)
    .compact();
```

Key points if you ever need to touch this code:

- **`kid` header must equal `ENABLEBANKING_APP_ID`** — Enable Banking uses it to look up the public
  key you registered in step 2.
- **`aud` must be a plain string**, not a JSON array. jjwt's default `.audience().add(...)` API
  produces `"aud": ["api.enablebanking.com"]`, which Enable Banking rejects with
  `401 {"code":401,"message":"JWT audience is not valid"}`. The deprecated-but-correct
  `.audience().single(...)` is used deliberately to emit `"aud": "api.enablebanking.com"`.
- Sent as `Authorization: Bearer <jwt>` on every request — there is no session/cookie state.

## 6. Verifying the integration end-to-end

With the app running and the variables above set:

1. **List ASPSPs** — confirms JWT auth works:

   ```bash
   curl -H "Authorization: Bearer <your NexaBudget JWT>" \
     "http://localhost:8080/api/banking/enable-banking/banks?countryCode=IT"
   ```

   A `401 {"code":401,...}` from *Enable Banking itself* (visible in the app logs, not the HTTP
   response to your client) at this step means the JWT signing is misconfigured — see
   Troubleshooting below.

2. **Start a link** for an existing local account:

   ```bash
   curl -X POST -H "Authorization: Bearer <jwt>" -H "Content-Type: application/json" \
     -d '{"institutionId":"<id from step 1, format name|country>","localAccountId":"<uuid>"}' \
     http://localhost:8080/api/banking/enable-banking/link
   ```

   Open the returned `redirectUrl` in a browser, complete the bank's consent flow, and confirm you
   land on `ENABLEBANKING_REDIRECT_URL` with `?code=...&state=...` in the query string.

3. **Complete the session** with the received `code`:

   ```bash
   curl -X POST -H "Authorization: Bearer <jwt>" -H "Content-Type: application/json" \
     -d '{"code":"<code from redirect>"}' \
     http://localhost:8080/api/banking/enable-banking/<localAccountId>/session
   ```

   Should return the authorized accounts (`uid`, `name`, `iban`, `currency`).

4. **Link a provider account** and **trigger a sync**, then check
   `GET /api/accounts/{id}` for `provider: "ENABLE_BANKING"`, `isLinkedToExternal: true`, and
   (after the async sync completes) new transactions.

## 7. Troubleshooting

| Symptom | Root cause | Fix |
| :--- | :--- | :--- |
| `401 {"code":401,"message":"JWT audience is not valid"}` on any Enable Banking call | `aud` claim emitted as a JSON array instead of a plain string | Ensure `EnableBankingService.currentToken()` uses `.audience().single(...)`, not `.audience().add(...)`. Already fixed in this codebase — if you see this, check for a regression. |
| `422 {"error":"WRONG_REQUEST_PARAMETERS", "detail":[{"loc":["body","aspsp","country"],"input":null}, ...]}` when starting a link | `institutionId` sent to `POST /link` doesn't encode the country (e.g. `"BBVA"` instead of `"BBVA\|IT"`) | The frontend must use the `id` field **exactly as returned** by `GET /banks` — it already encodes `name` and `country` joined by a pipe. Don't reconstruct it from `name` alone. |
| `422 {"detail":[{"loc":["body","redirect_url"],"msg":"Input should be a valid URL, relative URL without a base"}]}` | `ENABLEBANKING_REDIRECT_URL` is unset or a relative path (e.g. `/banking/enable-banking/callback`) | Set it to a full absolute URL (`https://.../banking/enable-banking/callback`) matching what's registered in the control panel. |
| `503 Service Unavailable` on every `/api/banking/enable-banking/...` call, app boots fine | `ENABLEBANKING_APP_ID`/`ENABLEBANKING_PRIVATE_KEY` unset, or the key failed to parse (check startup logs for `ENABLEBANKING_PRIVATE_KEY non valida` or `Enable Banking non configurato`) | This is the intended graceful-degradation behavior, not a crash — Enable Banking is optional. Fix the env vars and restart; `EnableBankingService.isConfigured()` flips to `true` once they're valid. Re-export the key as PKCS8 if the log mentions a parse failure (see §2, `openssl pkcs8 -topk8`). |
| `requiresReauth: true` on `AccountResponse` after a previously-working sync | Enable Banking session expired (analogous to a GoCardless requisition expiring) | Detected as a 401/403 from `/accounts/{uid}/transactions`, translated to `BankReauthRequiredException`. Re-run the link flow (§4) for that account — same UX as GoCardless re-auth. |
| Only some transactions imported on a large history | `MAX_TRANSACTION_PAGES` (50) reached while paginating `continuation_key` | A warning is logged (`Raggiunto il limite di 50 pagine ...`); it will fully catch up on the next scheduled sync since dedup is by `externalId` scoped to the account. Not a bug, just a safety cap — no silent data loss across syncs. |

## 8. Native image builds

`EnableBankingService` and every Enable Banking DTO are registered via
`@RegisterReflectionForBinding` for GraalVM native-image compatibility (mirrors the pattern already
used by `GocardlessService`). If you add a new field or DTO to the Enable Banking integration,
verify `./mvnw clean package -Pnative` still succeeds — a missing reflection hint fails silently at
runtime (empty/null deserialized fields) rather than at compile time.

## See also

- [API_GUIDE.md](API_GUIDE.md) — full `/api/banking/{provider}/...` endpoint reference.
- [ARCHITECTURE.md](ARCHITECTURE.md) — the `BankAggregationProvider` strategy pattern and how
  `AccountService` dispatches between GoCardless and Enable Banking.
- [DATA_MODEL.md](DATA_MODEL.md) — the `accounts.provider` column and the `BankProvider` enum.
- [DEPLOYMENT.md](DEPLOYMENT.md) — where these env vars fit alongside the rest of the required
  configuration.
