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

### 2. Crypto Portfolio (Binance Integration)

* **Binance Sync:** Users can provide read-only Binance API Keys to sync their spot balances.
* **Holdings Tracking:** Crypto balances are tracked alongside traditional fiat accounts.
* **Pricing Cache:** Real-time crypto prices are cached in Valkey/Redis for 5 minutes.

### 3. Budgeting & Alerts

* **Budgets & Templates:** Create budgets for specific categories. Templates allow recurring budget creation.
* **Budget Alerts:** Set percentage-based thresholds on budgets. The system will trigger alerts (e.g., via Email) when spending exceeds limits.

### 4. AI Integrations (Google Gemini via Spring AI)

* **Auto-Categorization:** When banking transactions are synced, Gemini analyzes the descriptions to automatically assign a category.
* **AI Reports:** Users can request financial analysis reports. Transactions are converted to CSV and passed to Gemini to generate insights as a PDF.
* **Financial Chatbot:** An AI chatbot provides insights based on the user's spending habits.
* **Semantic Caching:** Chatbot and categorization queries use embeddings stored in MongoDB Atlas to cache responses for similar requests, optimizing performance and cost.

## API Structure

The API is exposed via standard REST controllers, primarily secured by JWT.

| Controller | Responsibility |
| :--- | :--- |
| `AuthController` | Login, Registration, JWT issuance. |
| `ApiKeyController` | Management of M2M API Keys. |
| `AccountController` | CRUD operations for bank and manual accounts. |
| `TransactionController` | CRUD operations for transactions. |
| `CategoryController` | Managing user-defined or default transaction categories. |
| `BudgetController` | Managing budget limits per category. |
| `BudgetAlertController` | Setting and retrieving threshold alerts. |
| `BudgetTemplateController` | Reusable templates for budgets. |
| `GocardlessController` | Initiating bank linking and webhook handling. |
| `CryptoPortfolioController` | Syncing and retrieving Binance holdings. |
| `ChatController` | Interacting with the AI financial assistant. |
| `ReportController` | Triggering asynchronous AI report generation. |
| `TrashController` | Viewing and restoring soft-deleted items. |
| `AuditLogController` | Viewing the audit trail of entity modifications. |
| `ImportController` | Importing historical data (e.g., CSV). |
| `UserController` | Managing user profile and settings. |
