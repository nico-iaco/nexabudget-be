# NexaBudget - Backend

This is the backend service for **NexaBudget**, a personal finance management application. It provides a robust, highly scalable RESTful API built on **Java 25** and **Spring Boot 4.x**, featuring integrations with Open Banking, cryptocurrency exchanges (Binance, Coinbase), and Google Gemini AI.

## 📚 Documentation Index

We have organized the technical documentation into specialized modules within the `docs/` directory. Please refer to these for in-depth details:

| Document | Description |
| :--- | :--- |
| **[Architecture Guide](./docs/ARCHITECTURE.md)** | Layered design, Virtual Threads, async processing, and system diagrams. |
| **[Data Model](./docs/DATA_MODEL.md)** | PostgreSQL relational schema, MongoDB vector store, and ER diagrams. |
| **[API & Features Guide](./docs/API_GUIDE.md)** | Breakdown of core features (GoCardless, Binance, Coinbase, AI Reports) and controller list. |
| **[Security Architecture](./docs/SECURITY.md)** | JWT, M2M API Keys, cryptography, and rate limiting details. |
| **[Deployment Guide](./docs/DEPLOYMENT.md)** | Docker, GraalVM Native Image, and Kubernetes Kustomize instructions. |

---

## 🚀 Quick Start

Follow these steps to get a local copy up and running.

### 1. Prerequisites

- **JDK 25** or newer
- **Maven** 3.9+
- **Docker & Docker Compose** (Recommended for infrastructure)

### 2. Infrastructure Setup

The easiest way to start the required services (PostgreSQL, Redis, MongoDB, Mailhog) is via Docker Compose:

```bash
docker-compose up -d
```

### 3. Environment Configuration

Ensure the following environment variables are set (e.g., in a `.env` file):

| Variable | Description |
| :--- | :--- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/nexabudget` |
| `DB_PWD` | Your PostgreSQL password |
| `JWT_SECRET` | Secret key for signing (min 32 chars) |
| `GEMINI_API_KEY` | Your Google Gemini API Key |
| `MONGODB_URI` | `mongodb://localhost:27017/nexabudget-be` |
| `CRYPTO_ENCRYPTION_KEY` | 32-char key for Binance and Coinbase API encryption |

> Note: Coinbase Advanced Trade credentials (API Key Name + Private Key) are stored per user via the API and encrypted with `CRYPTO_ENCRYPTION_KEY`.

### 4. Build and Run

```bash
# Build the project
./mvnw clean install

# Run the application
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`.

---

## 🛠️ API & Monitoring

- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Actuator Health**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- **Prometheus Metrics**: [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)

All protected endpoints require a `Authorization: Bearer <JWT>` or `X-Api-Key: <KEY>` header.
