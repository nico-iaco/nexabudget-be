# NexaBudget - Backend
This is the backend service for NexaBudget, a personal finance management application. It provides a RESTful API for handling users, accounts, transactions, budgets, and categories.
## Built With
- Java 21
- Spring Boot 3.5.5
- Spring Data JPA
- Spring Security with JWT Authentication
- Maven
- Lombok
- SpringDoc OpenAPI (Swagger UI)
- PostgreSQL
- Redisson 3.52.0 (advanced Redis client)
- Valkey/Redis (for caching)
- Google Gemini AI (for transaction categorization)
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

- **Budget**: Represents a spending or saving goal for a specific category.
  - `id` (Primary Key, UUID)
  - `budget_limit` (BigDecimal)
  - `start_date`
  - `end_date`
  - `category_id` (Foreign Key to Category, UUID)
  - `user_id` (Foreign Key to User, UUID)
  - `created_at`
## Getting Started

To get a local copy up and running, follow these simple steps.

### Prerequisites

- JDK 21 or newer
- Maven
- Docker and Docker Compose (optional, for containerized deployment)

### 1. Clone the repository
```shell
git clone <your-repository-url>
cd nexaBudget-be
```

### 2. Running the Application
You can run the application either locally using Maven or with Docker.

#### Running Locally with Maven
This method is ideal for development and debugging.

**1. Database Setup**

You need a running instance of PostgreSQL. Set the following environment variables or update `src/main/resources/application.properties`:

```properties
# Database Configuration (use environment variables or set directly)
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/nexabudget}
spring.datasource.username=nexabudget-be
spring.datasource.password=${DB_PWD:your_password}
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA Configuration
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.open-in-view=false

# JWT Configuration
app.jwtSecret=tua-chiave-segreta-molto-lunga-e-sicura-di-almeno-64-caratteri
app.jwtExpirationInMs=86400000

# GoCardless Integration (optional)
gocardless.integrator.baseUrl=http://localhost:3000

# Google Gemini AI Configuration (required for AI categorization)
google.ai.apiKey=${GEMINI_API_KEY:your_gemini_api_key}
# Redis/Valkey Cache Configuration
spring.cache.type=redis
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.username=${REDIS_USERNAME:}
spring.data.redis.database=0
spring.data.redis.ssl.enabled=false
# Actuator and Monitoring
management.endpoints.web.exposure.include=*
management.endpoint.health.show-details=always
```

**Required Environment Variables:**
- `DB_URL`: PostgreSQL database URL (default: jdbc:postgresql://localhost:5432/nexabudget)
- `DB_PWD`: PostgreSQL database password
- `GEMINI_API_KEY`: Google Gemini API key for AI categorization

**Optional Environment Variables:**

- `REDIS_HOST`: Redis/Valkey host (default: localhost)
- `REDIS_PORT`: Redis/Valkey port (default: 6379)
- `REDIS_USERNAME`: Redis/Valkey username (default: empty)
- `REDIS_PASSWORD`: Redis/Valkey password (default: empty)
- `REDIS_SSL_ENABLED`: Enable SSL for Redis connection (default: false)
- `REDIS_HEALTH_CHECK`: Enable Redis health check (default: true, disable for restricted ACLs)

**2. Build the project**

Use Maven to build the project and download dependencies.

```shell
./mvnw clean install
```

**3. Run the application**

You can run the application using the Spring Boot Maven plugin.

```shell
./mvnw spring-boot:run
```

The application will start on http://localhost:8080.

#### Running with Docker

This is the recommended way to run the application in a production-like environment. The following commands will start
the application, PostgreSQL database, and Valkey cache.

**1. Run the JVM-based image**

This command builds the JVM image and starts all services (app, PostgreSQL, Valkey) in detached mode.

```shell
docker-compose up --build -d
```

**2. Run the Native image**

For better performance and a smaller memory footprint, you can run the native-compiled version.

```shell
docker-compose -f docker-compose.native.yml up --build -d
```

In both cases, the application will be available at http://localhost:8080, PostgreSQL at port 5432, and Valkey at port
6379.

To stop and remove the containers, use:
```shell
# For JVM
docker-compose down

# For Native
docker-compose -f docker-compose.native.yml down
```

## API Documentation

Once the application is running, you can access the Swagger UI documentation at:
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs

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
- `GET /api/transactions` - Get all user transactions (with pagination and filtering)
- `POST /api/transactions` - Create new transaction
- `GET /api/transactions/{id}` - Get transaction details
- `PUT /api/transactions/{id}` - Update transaction
- `DELETE /api/transactions/{id}` - Delete transaction
- `POST /api/transactions/{id}/categorize` - AI categorization of transaction

### Categories (Protected)
- `GET /api/categories` - Get all categories (default + user custom)
- `POST /api/categories` - Create custom category
- `PUT /api/categories/{id}` - Update category
- `DELETE /api/categories/{id}` - Delete category

### Budgets (Protected)
- `GET /api/budgets` - Get all user budgets
- `POST /api/budgets` - Create new budget
- `GET /api/budgets/{id}` - Get budget details
- `PUT /api/budgets/{id}` - Update budget
- `DELETE /api/budgets/{id}` - Delete budget

### GoCardless Integration (Protected)
- `POST /api/gocardless/requisitions` - Create requisition for bank account linking
- `GET /api/gocardless/requisitions/{id}` - Get requisition status
- `GET /api/gocardless/requisitions/{id}/accounts` - Get accounts from requisition
- `POST /api/gocardless/sync/{accountId}` - Sync transactions from external account

All protected endpoints require a valid JWT token in the Authorization header:
```
Authorization: Bearer <your_jwt_token>
```

## Features

### JWT Authentication
The application uses JWT (JSON Web Tokens) for secure authentication. Tokens expire after 24 hours by default (configurable via `app.jwtExpirationInMs` property).

### AI-Powered Transaction Categorization
Transactions can be automatically categorized using Google's Gemini AI. The AI analyzes transaction descriptions and suggests the most appropriate category based on your existing categories.

### GoCardless Integration
Connect your real bank accounts through GoCardless (formerly Nordigen) to automatically import transactions. The integration supports:
- Creating bank connection requisitions
- Linking external accounts
- Syncing transactions from linked accounts

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
- **Health**: http://localhost:8080/actuator/health
- **Metrics**: http://localhost:8080/actuator/metrics
- **Prometheus**: http://localhost:8080/actuator/prometheus

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
| `GEMINI_API_KEY`                | Google Gemini API key for AI categorization | Yes      | -                                                              |
| `REDIS_HOST`                    | Redis/Valkey host for caching               | No       | localhost                                                      |
| `REDIS_PORT`                    | Redis/Valkey port                           | No       | 6379                                                           |
| `REDIS_USERNAME`                | Redis/Valkey username                       | No       | (empty)                                                        |
| `REDIS_PASSWORD`                | Redis/Valkey password                       | No       | (empty)                                                        |
| `REDIS_SSL_ENABLED`             | Enable SSL for Redis                        | No       | false                                                          |
| `REDIS_HEALTH_CHECK`            | Enable Redis health check                   | No       | true                                                           |
| `JWT_SECRET`                    | JWT signing secret key                      | No       | tua-chiave-segreta-molto-lunga-e-sicura-di-almeno-64-caratteri |
| `app.jwtExpirationInMs`         | JWT token expiration time in milliseconds   | No       | 86400000 (24 hours)                                            |
| `gocardless.integrator.baseUrl` | GoCardless integrator service URL           | No       | http://localhost:3000                                          |

## Development

### Project Structure
```
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


