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

## Actuator and Monitoring

* **Endpoints:** Prometheus metrics, Health, and Info endpoints are exposed at `/actuator/*`.
* **Probes:** Kubernetes Liveness and Readiness probes are natively integrated (`management.health.livenessState.enabled=true`).
