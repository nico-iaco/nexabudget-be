# Sistema di Logging NexaBudget

## Panoramica

L'applicazione NexaBudget utilizza un sistema di logging strutturato ottimizzato per l'esecuzione in Kubernetes. I log
sono in formato JSON per facilitare l'aggregazione e l'analisi tramite strumenti come ELK Stack (Elasticsearch,
Logstash, Kibana) o Loki.

## Caratteristiche Principali

### 1. Log Strutturati in Formato JSON

- **Produzione/Kubernetes**: Log in formato JSON tramite Logstash encoder
- **Sviluppo Locale**: Log in formato leggibile per facilitare il debug
- Tracciamento automatico di `requestId`, `userId`, `username`, `endpoint`, `httpMethod`

### 2. Livelli di Log

| Livello   | Utilizzo                                                 |
|-----------|----------------------------------------------------------|
| **ERROR** | Errori gravi che richiedono attenzione immediata         |
| **WARN**  | Situazioni anomale che non bloccano l'esecuzione         |
| **INFO**  | Eventi significativi del ciclo di vita dell'applicazione |
| **DEBUG** | Informazioni dettagliate utili per il debugging          |

### 3. Contesto Request (MDC)

Ogni log include automaticamente:

- `requestId`: ID univoco per tracciare le richieste
- `userId`: ID dell'utente autenticato
- `username`: Username dell'utente autenticato
- `endpoint`: URL dell'endpoint chiamato
- `httpMethod`: Metodo HTTP (GET, POST, etc.)

### 4. Gestione Errori Centralizzata

Il `GlobalExceptionHandler` gestisce tutte le eccezioni in modo uniforme:

- Validazione input → 400 Bad Request
- Autenticazione fallita → 401 Unauthorized
- Risorsa non trovata → 404 Not Found
- Conflitti di stato → 409 Conflict
- Errori interni → 500 Internal Server Error

## Configurazione

### Profili Spring

#### Locale/Sviluppo (`local`, `dev`) - Log Leggibili

```properties
spring.profiles.active=local
```

**Output**: Log in formato leggibile e colorato per console, ideale per debugging

**Esempio:**

```
2025-11-06 10:15:30.123 [http-nio-8080-exec-1] INFO  i.i.n.service.AuthService [a1b2c3d4] [john.doe] - Login riuscito
```

#### Produzione/Kubernetes (`prod`, `kubernetes`) - Log JSON

```properties
spring.profiles.active=prod
```

**Output**: Log in formato JSON strutturato per aggregazione e analisi

**Esempio:**

```json
{
  "@timestamp": "2025-11-06T10:15:30.123Z",
  "level": "INFO",
  "logger_name": "it.iacovelli.nexabudgetbe.service.AuthService",
  "message": "Login riuscito per l'utente: john.doe",
  "requestId": "a1b2c3d4-...",
  "username": "john.doe"
}
```

#### Nessun Profilo - Default Locale

```properties
# Nessun profilo impostato
```

**Output**: Per compatibilità, usa log leggibili come ambiente locale

### Variabili d'Ambiente

```bash
# Livello di log (opzionale, default: INFO)
LOGGING_LEVEL_ROOT=INFO

# Path per file di log (opzionale)
LOG_FILE=/var/log/nexabudget/app.log
```

## Monitoraggio in Kubernetes

### Health Check Endpoints

L'applicazione espone endpoint per il monitoraggio:

```bash
# Health check generale
GET /actuator/health

# Liveness probe (l'app è attiva?)
GET /actuator/health/liveness

# Readiness probe (l'app è pronta a ricevere traffico?)
GET /actuator/health/readiness

# Metriche Prometheus
GET /actuator/prometheus
```

### Configurazione Kubernetes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

## Esempi di Log

### Formato JSON (Produzione con profilo `prod` o `kubernetes`)

```json
{
  "@timestamp": "2025-11-06T10:15:30.123Z",
  "level": "INFO",
  "logger_name": "it.iacovelli.nexabudgetbe.service.AuthService",
  "message": "Login riuscito per l'utente: john.doe (ID: 123e4567-e89b-12d3-a456-426614174000)",
  "thread_name": "http-nio-8080-exec-1",
  "app": "nexabudget-be",
  "version": "0.0.1",
  "requestId": "a1b2c3d4-5e6f-7g8h-9i0j-k1l2m3n4o5p6",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "username": "john.doe",
  "endpoint": "/api/auth/login",
  "httpMethod": "POST"
}
```

### Formato Leggibile (Locale con profilo `local` o `dev`, o senza profilo)

```
2025-11-06 10:15:30.123 [http-nio-8080-exec-1] INFO  i.i.n.service.AuthService [a1b2c3d4-5e6f-7g8h-9i0j-k1l2m3n4o5p6] [john.doe] - Login riuscito per l'utente: john.doe (ID: 123e4567-e89b-12d3-a456-426614174000)
```

## Best Practices per i Sviluppatori

### 1. Utilizzo dei Logger

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class MyService {
    private static final Logger logger = LoggerFactory.getLogger(MyService.class);

    public void myMethod() {
        logger.info("Operazione avviata");
        logger.debug("Dettagli: {}", details);
        logger.warn("Attenzione: {}", warning);
        logger.error("Errore critico: {}", error, exception);
    }
}
```

### 2. Logging nelle Operazioni

#### Operazioni di Successo

```java
logger.info("Utente creato con successo: {} (ID: {})",username, userId);
```

#### Operazioni Fallite

```java
logger.error("Errore durante la creazione dell'utente: {}",username, exception);
```

#### Operazioni di Business

```java
logger.info("Sincronizzazione transazioni completata per account ID: {}",accountId);
```

### 3. Non Loggare Dati Sensibili

❌ **NO:**

```java
logger.info("Password: {}",password);
logger.

debug("Token JWT: {}",jwtToken);
```

✅ **SÌ:**

```java
logger.info("Utente autenticato: {}",username);
logger.

debug("Token generato per utente: {}",username);
```

## Analisi dei Log

### Query Esempio per Elasticsearch/Kibana

```javascript
// Tutti gli errori nelle ultime 24 ore
level: "ERROR"
AND
@timestamp >=
now - 24
h

// Log di un utente specifico
username: "john.doe"

// Log di un endpoint specifico
endpoint: "/api/transactions"

// Tracciare una richiesta specifica
requestId: "a1b2c3d4-5e6f-7g8h-9i0j-k1l2m3n4o5p6"

// Errori di autenticazione
message: *
"autenticazione" * AND
level: ("WARN"
OR
"ERROR"
)
```

### Metriche Utili

- **Tasso di errore**: Numero di log ERROR per minuto
- **Tempo di risposta**: Tracciato tramite metriche Prometheus
- **Utenti attivi**: Conteggio di `userId` univoci
- **Endpoint più utilizzati**: Frequenza di accesso per `endpoint`

## Troubleshooting

### I log non vengono visualizzati

1. Verifica il livello di log configurato
2. Controlla che il profilo Spring sia corretto
3. Verifica la configurazione di logback-spring.xml

### Log in formato sbagliato

#### Sviluppo locale (locale/dev)

```bash
# Imposta profilo local
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run

# O in application.properties
spring.profiles.active=local
```

#### Kubernetes (prod, kubernetes)

```bash
# Imposta profilo prod
export SPRING_PROFILES_ACTIVE=prod
./mvnw spring-boot:run

# In Kubernetes, nel ConfigMap
SPRING_PROFILES_ACTIVE: "prod"
```

#### Verifica quale profilo è attivo

```bash
# Controlla i log di startup, cerca questa riga:
# "The following profiles are active: prod"
```

### Troppi log DEBUG

Modifica il livello di log:

**Per ambiente locale** (`application-local.properties`):

```properties
logging.level.it.iacovelli.nexabudgetbe=INFO
```

**Per produzione** (`application-prod.properties`):

```properties
logging.level.it.iacovelli.nexabudgetbe=INFO
```

## Risorse Aggiuntive

- [Logback Documentation](http://logback.qos.ch/documentation.html)
- [Logstash Encoder](https://github.com/logfellow/logstash-logback-encoder)
- [SLF4J Documentation](http://www.slf4j.org/manual.html)
- [Spring Boot Logging](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.logging)

