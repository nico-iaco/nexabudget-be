# Deployment and Infrastructure

## Overview

NexaBudget Backend is containerized and cloud-ready. It can be deployed using Docker Compose for simple environments or Kubernetes (K8s) for highly available production setups. It also supports compiling to a **GraalVM Native Image** for extreme performance and low memory footprint.

## Docker Deployment

The project includes several Docker configurations:

* `Dockerfile`: Standard JVM-based container image.
* `docker-compose.yml`: Full stack definition including PostgreSQL, Redis/Valkey, Mailhog, and MongoDB.
* `docker-compose.native.yml`: Specific compose file for running the GraalVM native image version of the app.

### Running with Docker Compose

```bash
# Start infrastructure and JVM backend
docker compose up -d

# Start infrastructure and Native Image backend
docker compose -f docker-compose.native.yml up -d
```

## GraalVM Native Image

Spring Boot 4.0.5 is configured with GraalVM support to compile the application ahead-of-time (AOT).

* **Benefits:** Instant startup time (<100ms) and drastically reduced memory usage (tens of MBs instead of hundreds).
* **Build Command:**

  ```bash
  ./mvnw clean package -Pnative
  ```

* **Configuration:** Custom reflection hints are provided in `NativeRuntimeHints.java` and `GoogleGenAiRuntimeHints.java` to ensure third-party libraries (like Gemini and Coinbase SDKs) work seamlessly in native mode.

## Kubernetes Deployment

Kubernetes manifests are managed using **Kustomize**, structured in the `k8s/` directory.

### Structure

* `k8s/base/`: Contains the foundational resources:
  * `deployment.yaml`: Replicaset and container specs.
  * `service.yaml`: Internal ClusterIP service.
  * `ext-service.yaml`: LoadBalancer for external access.
* `k8s/overlays/prod/`: Production specific overrides (e.g., replicas, resource limits, environment variables mapping).

### Deployment Strategy

1. Configure environment variables in a Kubernetes Secret (e.g., `DB_URL`, `JWT_SECRET`, `GEMINI_API_KEY`).
2. Apply the Kustomize overlay:

   ```bash
   kubectl apply -k k8s/overlays/prod
   ```

## Required Environment Variables

| Variable | Purpose | Notes |
| :--- | :--- | :--- |
| `DB_URL`, `DB_PWD` | PostgreSQL connection | username hardcoded `nexabudget-be` |
| `JWT_SECRET` | HMAC SHA-256 signing key | ≥ 32 chars; **must** differ from dev default or boot fails |
| `CRYPTO_ENCRYPTION_KEY` | AES key for Binance/Coinbase credentials at rest | 32+ chars |
| `GEMINI_API_KEY` | Google Gemini access | — |
| `MONGODB_URI` | Atlas connection for semantic cache | vector index pre-created |
| `REDIS_HOST`, `REDIS_PORT` | Valkey/Redis | default `localhost:6379` |
| `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` | Optional Redis auth/TLS | SSL default `false` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PWD`, `SMTP_AUTH`, `SMTP_STARTTLS` | Outbound mail | dev uses Mailhog on `localhost:1025` |
| `MAIL_FROM` | Sender address | default `noreply@nexabudget.it` |
| `ENABLEBANKING_APP_ID`, `ENABLEBANKING_PRIVATE_KEY` | Enable Banking Cloud API auth | **Optional** — app id + RSA private key (PKCS8 PEM) from the control panel, see [ENABLE_BANKING_SETUP.md](ENABLE_BANKING_SETUP.md). If unset or unparseable, the app still boots normally and Enable Banking endpoints respond `503` instead of crashing startup — GoCardless is unaffected either way. |
| `ENABLEBANKING_REDIRECT_URL` | Enable Banking consent callback | Required only once the two vars above are set. **Must be an absolute URL**, registered verbatim in the control panel — relative paths are rejected by Enable Banking (422). |
| `ENABLEBANKING_BASE_URL`, `ENABLEBANKING_CONSENT_VALID_DAYS` | Enable Banking optional overrides | default `https://api.enablebanking.com`, `90` days |
| `VIRTUAL_THREADS_ENABLED` | Toggle Loom virtual threads | default `true` |
| `GEMINI_MODEL`, `NEXABUDGET_CHAT_MODEL`, `NEXABUDGET_REPORT_MODEL` | AI model overrides | — |
| `NEXABUDGET_BULK_CATEGORIZATION_TIMEOUT_SECONDS` | Bulk AI categorization timeout | default `120` |
| `SEMANTIC_CACHE_COLLECTION_NAME`, `SEMANTIC_CACHE_INDEX_NAME` | Atlas vector store overrides | — |

## Profiles

* **dev (default)** — verbose logging (`DEBUG` on app packages), Swagger UI enabled, Mailhog SMTP, actuator health details `always`.
* **prod (`-Dspring.profiles.active=prod`)** — `INFO` logging, restricted actuator health, Swagger UI disabled. Hibernate `ddl-auto=validate` — schema must be migrated manually before deploy (see [DATA_MODEL.md](DATA_MODEL.md)).

## Actuator and Monitoring

* **Exposed endpoints** (`management.endpoints.web.exposure.include`): `health`, `info`, `metrics`, `prometheus`.
* **Probes:** `livenessState` and `readinessState` are both enabled, so Kubernetes liveness/readiness probes can point at `/actuator/health/liveness` and `/actuator/health/readiness`.
* **Metrics:** Prometheus scrape target at `/actuator/prometheus` (Micrometer Prometheus registry).
* **Logging:** structured pattern includes `requestId` and `username` from MDC, populated by `LoggingFilter`.

## Build Hardening

* **Bouncy Castle** is registered at startup for EC key support (Coinbase Advanced Trade SDK).
* **Hibernate enhancements** (lazy init, dirty tracking) are applied at build time via `hibernate-maven-plugin`.
* **GraalVM reflection hints** are declared in `GoogleGenAiRuntimeHints.java` and `NativeRuntimeHints.java` so Spring AI and the Gemini/Coinbase SDKs work natively.
