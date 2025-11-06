# Configurazione Cache Valkey/Redis

## Descrizione

Il progetto utilizza **Spring Data Redis** con driver **Lettuce** per connettersi a Valkey (fork open-source di Redis)
come sistema di caching distribuito per migliorare le performance delle chiamate alle API di GoCardless.

### Perché Spring Data Redis?

Spring Data Redis offre un'integrazione nativa con Spring Boot:

- 🚀 **Integrazione perfetta**: Configurazione automatica con Spring Boot
- 🔧 **Compatibilità ACL**: Funziona con permessi Redis limitati (es. Aiven)
- 🎯 **Semplicità**: Usa solo comandi Redis base (GET, SET, DEL, EXPIRE)
- 📦 **Lettuce driver**: Client reattivo e performante incluso
- ✅ **Minimale**: Nessuna dipendenza esterna pesante

## Metodi Cachati

I seguenti metodi del `GocardlessService` sono cachati per **6 ore**:

### 1. `getBankAccounts(String requisitionId)`

- **Cache**: `bankAccounts`
- **Chiave**: `requisitionId`
- **TTL**: 6 ore
- **Descrizione**: Cacha l'elenco dei conti bancari associati a una requisition

### 2. `getGoCardlessTransaction(String requisitionId, String accountId)`

- **Cache**: `gocardlessTransactions`
- **Chiave**: `requisitionId_accountId`
- **TTL**: 6 ore
- **Descrizione**: Cacha le transazioni bancarie di un account specifico

## Configurazione

### Architettura

Il progetto utilizza:

- **Spring Data Redis** - Client Redis standard di Spring Boot
- **Lettuce** - Driver Redis reattivo (incluso in Spring Data Redis)
- **Valkey** - Server cache compatibile con Redis
- **Spring Cache** - Abstraction layer per il caching

### Variabili d'Ambiente

```properties
REDIS_HOST=localhost         # Default: localhost
REDIS_PORT=6379             # Default: 6379
REDIS_USERNAME=# Default: vuoto (nessuna username)
REDIS_PASSWORD=# Default: vuoto (nessuna password)
REDIS_SSL_ENABLED=false     # Solo per produzione
```

### Configurazione Spring Data Redis

### Configurazione Redisson

Il progetto usa **Spring Data Redis** con configurazione via properties. La configurazione è gestita automaticamente da
Spring Boot.
Il progetto usa una **configurazione programmatica di Redisson** tramite la classe `RedissonConfig.java`. Questo
approccio è più flessibile e type-safe rispetto ai file YAML.

#### Caratteristiche della Configurazione

```java

@EnableCaching
public class CacheConfig {

    public class RedissonConfig {
        public CacheManager cacheManager(
                RedisConnectionFactory connectionFactory,
                ObjectMapper objectMapper) {
            Config config = new Config();
            ObjectMapper cacheMapper = objectMapper.copy();
            cacheMapper.registerModule(new JavaTimeModule());
            cacheMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            String address = protocol + redisHost + ":" + redisPort;
            RedisCacheConfiguration config = RedisCacheConfiguration
                    .defaultCacheConfig()
                    .entryTtl(Duration.ofHours(6))  // TTL 6 ore
                    .disableCachingNullValues()
                    .serializeKeysWith(StringRedisSerializer)
                    .serializeValuesWith(GenericJackson2JsonRedisSerializer);
            .setRetryAttempts(3);
            return RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(config)
                    .build();
            return Redisson.create(config);
        }
    }
```

- ✅ Configurazione automatica Spring Boot
- ✅ Compatibile con ACL Redis restrittive (Aiven, AWS ElastiCache)
- ✅ Usa solo comandi Redis base: GET, SET, DEL, EXPIRE
- ✅ Supporto SSL nativo
- ✅ Serializzazione JSON con Jackson
- ✅ Nessuna configurazione esterna necessaria
- ✅ Facile da testare e debuggare

### Application Properties

# Cache configuration

Le seguenti proprietà sono configurate in `application.properties`:

# Redis connection

```properties
spring.cache.type=redis
spring.data.redis.username=${REDIS_USERNAME:}
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.ssl.enabled=false
# Disable health check if Redis user has limited permissions
management.health.redis.enabled=${REDIS_HEALTH_CHECK:true}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.database=0
```

# Cache configuration

# Redis connection (Aiven, AWS ElastiCache, etc.)

Per produzione (`application-prod.properties`):

```properties
spring.cache.type=redis
spring.data.redis.username=${REDIS_USERNAME:}
spring.data.redis.host=${REDIS_HOST}
spring.data.redis.ssl.enabled=${REDIS_SSL_ENABLED:false}
# Disable health check for limited ACL users
management.health.redis.enabled=false
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.database=0
spring.data.redis.ssl=${REDIS_SSL_ENABLED:false}
```

```

## Esecuzione con Docker

Il progetto include un servizio Valkey nel `docker-compose.yml`:

```bash
# Avvia tutti i servizi (PostgreSQL, Valkey, App)
docker-compose up -d

# Verifica lo stato di Valkey
docker logs nexabudget-valkey

# Connettiti a Valkey CLI
docker exec -it nexabudget-valkey valkey-cli
```

## Test

Durante i test, la cache è disabilitata tramite la proprietà in `application-test.properties`:

```properties
spring.cache.type=none
```

## Gestione Cache

### Verificare le chiavi in cache

```bash
# Connettiti a Valkey
docker exec -it nexabudget-valkey valkey-cli

# Lista tutte le chiavi
KEYS *

# Visualizza una chiave specifica
GET bankAccounts::your-requisition-id

# Cancella tutte le chiavi di una cache specifica
Configura il `CacheManager` di Spring con Spring Data Redis:
- Utilizza `RedisCacheManager` per la gestione cache
- Serializzazione JSON con `GenericJackson2JsonRedisSerializer`
docker exec -it nexabudget-valkey valkey-cli FLUSHALL
- Supporto per tipi Java 8+ (LocalDateTime, etc.)
- Configurazione basata su `RedisConnectionFactory` (auto-configurato da Spring Boot)

## Struttura delle Classi
Utilizza le annotazioni `@Cacheable` per abilitare il caching automatico sui metodi specificati:
```java
@Cacheable(value = BANK_ACCOUNTS_CACHE, key = "#requisitionId")
public List<GocardlessBankDetail> getBankAccounts(String requisitionId) {
    // ...
}

@Cacheable(value = GOCARDLESS_TRANSACTIONS_CACHE, 
           key = "#requisitionId + '_' + #accountId")
public List<GocardlessTransaction> getGoCardlessTransaction(
        String requisitionId, String accountId) {
## Comandi Redis Utilizzati

Spring Data Redis usa solo i comandi Redis **più basilari**, compatibili con qualsiasi ACL:

- **GET** - Lettura valori dalla cache
- **SET** - Scrittura valori in cache (con PSETEX per TTL)
- **DEL** - Eliminazione chiavi dalla cache
- **EXPIRE/PEXPIRE** - Impostazione TTL
- **EXISTS** - Verifica esistenza chiave

Questo rende la soluzione compatibile con:
- ✅ Aiven Redis (ACL restrittive)
- ✅ AWS ElastiCache
- ✅ Azure Cache for Redis
- ✅ Google Cloud Memorystore
- ✅ Valkey self-hosted

## ACL Redis Consigliata

Per garantire il corretto funzionamento, l'utente Redis deve avere almeno questi permessi:

```

~* +@read +@write +@keyspace

```

Oppure più specificamente:

```

~* +get +set +del +expire +pexpire +exists +ttl

```

Se l'utente ha permessi molto limitati, disabilita il health check:

```properties
management.health.redis.enabled=false
```

    // ...

}

### RedissonConfig.java

Configura il client Redisson programmaticamente:

- Bean `RedissonClient` con configurazione ottimizzata
- Supporto automatico SSL per produzione (rediss://)
- Spring Data Redis supporta automaticamente retry e reconnect tramite Lettuce
- Il pool di connessioni è gestito automaticamente da Spring Boot
- Codec JSON Jackson per serializzazione automatica
- Timeout configurabili per connessioni e operazioni

### CacheConfig.java

Configura il `CacheManager` di Spring con Redisson:

- Utilizza `RedissonSpringCacheManager` per la gestione cache
- Serializzazione JSON automatica tramite `JsonJacksonCodec`
- TTL di 6 ore per tutte le cache
- Configurazione separata per ogni cache (bankAccounts, gocardlessTransactions)

### GocardlessService.java

Utilizza le annotazioni `@Cacheable` per abilitare il caching automatico sui metodi specificati.

## Note

- Redisson offre performance superiori rispetto al client Redis standard
- La cache utilizza serializzazione JSON per garantire la persistenza cross-platform
- Il TTL di 6 ore bilancia performance e freschezza dei dati
- In ambiente di test, la cache è disabilitata per garantire l'isolamento dei test
- Valkey è 100% compatibile con Redis ma completamente open-source
- Redisson supporta automaticamente retry e reconnect in caso di errori
- Il pool di connessioni è ottimizzato per alta concorrenza

