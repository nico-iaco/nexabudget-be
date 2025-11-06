# Query di Esempio per Analisi Log NexaBudget

Questo documento contiene query di esempio per analizzare i log strutturati dell'applicazione NexaBudget Backend.

## Elasticsearch/Kibana Query (KQL - Kibana Query Language)

### Query Base

```kql
# Tutti i log dell'applicazione
app: "nexabudget-be"

# Log nelle ultime 24 ore
@timestamp >= now-24h

# Log di un livello specifico
level: "ERROR"
level: "WARN" OR level: "ERROR"
```

### Filtrare per Utente

```kql
# Log di un utente specifico
username: "john.doe"

# Tutti gli utenti autenticati
username: *

# Operazioni di un utente in un periodo
username: "john.doe" AND @timestamp >= now-1h
```

### Filtrare per Endpoint

```kql
# Tutte le chiamate all'endpoint di login
endpoint: "/api/auth/login"

# Tutte le operazioni su transazioni
endpoint: /api/transactions*

# Solo operazioni POST
httpMethod: "POST" AND endpoint: /api/transactions*
```

### Tracciamento Richieste

```kql
# Tracciare una richiesta specifica
requestId: "a1b2c3d4-5e6f-7g8h-9i0j-k1l2m3n4o5p6"

# Tutte le richieste con errori
requestId: * AND level: "ERROR"
```

### Analisi Errori

```kql
# Tutti gli errori di autenticazione
message: *autenticazione* AND level: ("WARN" OR "ERROR")

# Errori di validazione
message: *validazione*

# Errori del database
logger_name: *repository* AND level: "ERROR"

# Errori nelle chiamate esterne (GoCardless)
logger_name: *GocardlessService* AND level: "ERROR"
```

### Query Avanzate

```kql
# Richieste lente (se usi metriche di timing)
duration > 5000

# Errori frequenti nelle ultime 24 ore
level: "ERROR" AND @timestamp >= now-24h
# Poi usa aggregazioni per contare per logger_name

# Login falliti
endpoint: "/api/auth/login" AND level: "WARN"

# Operazioni di sincronizzazione
message: *sincronizzazione*
```

## Loki Query (LogQL)

```logql
# Log base
{app="nexabudget-be"}

# Con filtro per livello
{app="nexabudget-be"} |= "ERROR"

# Log di un utente specifico
{app="nexabudget-be"} | json | username="john.doe"

# Conteggio errori per minuto
rate({app="nexabudget-be"} |= "ERROR" [5m])

# Log di autenticazione fallita
{app="nexabudget-be"} | json | endpoint="/api/auth/login" | level="WARN"

# Top 10 endpoint più chiamati
topk(10, count_over_time({app="nexabudget-be"} | json | __error__="" [1h]) by (endpoint))
```

## Grafana Loki Dashboard Query

### Pannello: Tasso di Errore

```logql
sum(rate({app="nexabudget-be"} | json | level="ERROR" [5m]))
```

### Pannello: Richieste per Endpoint

```logql
sum by (endpoint) (rate({app="nexabudget-be"} | json | __error__="" [5m]))
```

### Pannello: Utenti Attivi

```logql
count(count_over_time({app="nexabudget-be"} | json | username!="" [5m]) by (username))
```

### Pannello: Distribuzione Livelli di Log

```logql
sum by (level) (count_over_time({app="nexabudget-be"} | json [5m]))
```

## kubectl - Query da Command Line

```bash
# Vedere tutti i log JSON
kubectl logs deployment/nexabudget-be

# Solo errori
kubectl logs deployment/nexabudget-be | grep '"level":"ERROR"'

# Log nelle ultime 1 ora
kubectl logs deployment/nexabudget-be --since=1h

# Log di un utente specifico
kubectl logs deployment/nexabudget-be | grep '"username":"john.doe"'

# Follow logs in tempo reale
kubectl logs -f deployment/nexabudget-be

# Log da tutti i pod del deployment
kubectl logs -l app=nexabudget-be --all-containers=true

# Esportare log in un file
kubectl logs deployment/nexabudget-be > nexabudget-logs.json

# Contare gli errori
kubectl logs deployment/nexabudget-be | grep '"level":"ERROR"' | wc -l

# Estrarre tutti i requestId univoci
kubectl logs deployment/nexabudget-be | grep -o '"requestId":"[^"]*"' | sort | uniq
```

## jq - Parse JSON da Command Line

```bash
# Installare jq: brew install jq (macOS) o apt-get install jq (Linux)

# Log formattati leggibili
kubectl logs deployment/nexabudget-be | jq -r '"\(.timestamp) [\(.level)] \(.logger_name) - \(.message)"'

# Solo errori formattati
kubectl logs deployment/nexabudget-be | jq 'select(.level == "ERROR")'

# Statistiche per livello di log
kubectl logs deployment/nexabudget-be | jq -r '.level' | sort | uniq -c

# Estrarre specifici campi
kubectl logs deployment/nexabudget-be | jq '{timestamp, level, username, endpoint, message}'

# Top 10 endpoint più chiamati
kubectl logs deployment/nexabudget-be | jq -r '.endpoint' | sort | uniq -c | sort -rn | head -10

# Log di un utente specifico formattati
kubectl logs deployment/nexabudget-be | jq 'select(.username == "john.doe")'

# Tracciare una richiesta completa
kubectl logs deployment/nexabudget-be | jq 'select(.requestId == "abc-123-def")'
```

## Prometheus Metrics Query (PromQL)

```promql
# Rate di richieste HTTP
rate(http_server_requests_seconds_count[5m])

# Percentile 95 del tempo di risposta
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# Tasso di errori HTTP 5xx
rate(http_server_requests_seconds_count{status=~"5.."}[5m])

# JVM Memory usage
jvm_memory_used_bytes{area="heap"}

# Numero di thread attivi
jvm_threads_live_threads
```

## Alert Rules (Prometheus AlertManager)

```yaml
groups:
  - name: nexabudget-alerts
    rules:
      # Alert per tasso di errore elevato
      - alert: HighErrorRate
        expr: rate({app="nexabudget-be"} | json | level="ERROR" [5m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Tasso di errore elevato per NexaBudget"
          description: "Il tasso di errore è {{ $value }} errori/secondo"

      # Alert per pod non pronto
      - alert: PodNotReady
        expr: kube_pod_status_ready{pod=~"nexabudget-be-.*"} == 0
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Pod NexaBudget non pronto"
          description: "Il pod {{ $labels.pod }} non è pronto da 5 minuti"

      # Alert per memoria alta
      - alert: HighMemoryUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Utilizzo memoria elevato"
          description: "Memoria heap utilizzata al {{ $value | humanizePercentage }}"
```

## Esempi di Analisi Comuni

### 1. Trovare Problemi di Performance

```bash
# Endpoint con più errori
kubectl logs deployment/nexabudget-be | \
  jq 'select(.level == "ERROR") | .endpoint' | \
  sort | uniq -c | sort -rn | head -10

# Operazioni lente (se loggi il tempo)
kubectl logs deployment/nexabudget-be | \
  jq 'select(.duration > 5000)'
```

### 2. Audit Trail di un Utente

```bash
# Tutte le operazioni di un utente
kubectl logs deployment/nexabudget-be --since=24h | \
  jq 'select(.username == "john.doe") | {timestamp, endpoint, httpMethod, message}'
```

### 3. Debugging di una Richiesta Specifica

```bash
# Tracciare tutti i log di una richiesta
REQUEST_ID="abc-123-def"
kubectl logs deployment/nexabudget-be | \
  jq "select(.requestId == \"$REQUEST_ID\")" | \
  jq -r '"\(.timestamp) [\(.level)] \(.logger_name) - \(.message)"'
```

### 4. Statistiche Giornaliere

```bash
# Log per livello nelle ultime 24 ore
kubectl logs deployment/nexabudget-be --since=24h | \
  jq -r '.level' | sort | uniq -c

# Endpoint più chiamati oggi
kubectl logs deployment/nexabudget-be --since=24h | \
  jq -r '.endpoint' | grep -v null | sort | uniq -c | sort -rn | head -20
```

## Dashboard Suggerite

### Grafana Dashboard - NexaBudget Monitoring

**Pannelli consigliati:**

1. **Requests per Second** (Time Series)
    - Query: `sum(rate(http_server_requests_seconds_count[5m]))`

2. **Error Rate** (Time Series)
    - Query: `sum(rate({app="nexabudget-be"} | json | level="ERROR" [5m]))`

3. **Response Time P95** (Gauge)
    - Query: `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))`

4. **Active Users** (Stat)
    - Query: `count(count_over_time({app="nexabudget-be"} | json | username!="" [5m]) by (username))`

5. **Top Endpoints** (Bar Chart)
    - Query: `topk(10, sum by (endpoint) (rate({app="nexabudget-be"} | json [5m])))`

6. **Error Logs** (Logs Panel)
    - Query: `{app="nexabudget-be"} | json | level="ERROR"`

7. **JVM Memory** (Time Series)
    - Query: `jvm_memory_used_bytes{area="heap"}`

8. **HTTP Status Codes** (Pie Chart)
    - Query: `sum by (status) (rate(http_server_requests_seconds_count[5m]))`

## Risorse Utili

- [Kibana Query Language (KQL)](https://www.elastic.co/guide/en/kibana/current/kuery-query.html)
- [LogQL - Loki Query Language](https://grafana.com/docs/loki/latest/logql/)
- [PromQL - Prometheus Query Language](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [jq Manual](https://stedolan.github.io/jq/manual/)

