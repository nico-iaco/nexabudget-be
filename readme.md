# NexaBudget - Backend
This is the backend service for NexaBudget, a personal finance management application. It provides a RESTful API for handling users, accounts, transactions, budgets, and categories.
## Built With
- Java
- Spring Boot
- Spring Data JPA
- Spring Security
- Maven
## Database Schema
The application uses a relational database to persist data. The main entities are:
- **User**: Represents a user of the application.
  - `id` (Primary Key)
  - `username`
  - `email`
  - `password`
- **Account**: Represents a financial account (e.g., bank account, credit card).
  - `id` (Primary Key)
  - `name`
  - `type` (e.g., CASH, CHECKING, SAVINGS)
  - `user_id` (Foreign Key to User)
- **Category**: Represents a category for transactions (e.g., Food, Salary).
  - `id` (Primary Key)
  - `name`
  - `type` (e.g., INCOME, EXPENSE)
- **Transaction**: Represents a single financial transaction.
  - `id` (Primary Key)
  - `amount`
  - `description`
  - `date`
  - `type` (e.g., INCOME, EXPENSE)
  - `account_id` (Foreign Key to Account)
  - `category_id` (Foreign Key to Category)
- **Budget**: Represents a spending or saving goal for a specific category.
  - `id` (Primary Key)
  - `amount`
  - `startDate`
  - `endDate`
  - `category_id` (Foreign Key to Category)
  - `user_id` (Foreign Key to User)
## Getting Started
To get a local copy up and running, follow these simple steps.
### Prerequisites
- JDK 17 or newer
- Maven
- A running instance of a postgres database.
### Local Development Setup
1. **Clone the repository**
    ```shell
    git clone <your-repository-url>
    cd nexaBudget-be
    ```
2. **Configure the database**
    Open the `src/main/resources/application.properties` file and update the database connection properties. For example, for PostgreSQL:
    ```properties
    spring.datasource.url=jdbc:postgresql://localhost:5432/nexabudget
    spring.datasource.username=your_username
    spring.datasource.password=your_password
    spring.datasource.driver-class-name=org.postgresql.Driver
    
    # Instruct Hibernate to generate the database schema
    spring.jpa.hibernate.ddl-auto=update
    ```
3. **Build the project**
    Use Maven to build the project and download dependencies.
    ```shell
    ./mvnw clean install
    ```
4. **Run the application**
    You can run the application using the Spring Boot Maven plugin.
    ```shell
    ./mvnw spring-boot:run
    ```
    The application will start on http://localhost:8080.