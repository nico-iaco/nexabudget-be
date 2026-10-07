# Immagine Maven usata sia dal builder JVM sia (solo per i binari di Maven) dal builder nativo
FROM maven:3.9-eclipse-temurin-25 AS maven-base

# Stage 1: Builder con cache delle dipendenze
FROM maven-base AS builder-jvm
WORKDIR /app
# Copia solo il pom.xml per cachare le dipendenze
COPY pom.xml .
RUN mvn -B dependency:go-offline
# Copia il resto e compila
COPY src ./src
RUN mvn -B package -DskipTests

# Stage 2: Immagine JVM ottimizzata
FROM eclipse-temurin:25-jre-alpine AS jvm
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=builder-jvm /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

# Stage 3: Builder Native
FROM ghcr.io/graalvm/native-image-community:25 AS builder-native
WORKDIR /app
# Maven copiato direttamente dall'immagine ufficiale (evita il segfault di microdnf su QEMU).
# NON usare --from=builder-jvm: costringerebbe BuildKit a eseguire tutta la build JVM prima di quella nativa.
COPY --from=maven-base /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
ENV PATH=${MAVEN_HOME}/bin:${PATH}

COPY pom.xml .
RUN mvn -B dependency:go-offline -Pnative
COPY src ./src
# Opzioni extra per native-image, lette dall'ambiente (es. "-Ob" per build rapide non ottimizzate nelle beta delle PR)
ARG NATIVE_IMAGE_OPTIONS=""
# Solo il binario resta nel layer: target/ (jar, classi, sorgenti AOT) appesantirebbe l'export della cache
RUN mvn -B -Pnative package -DskipTests \
    && mv target/nexaBudget-be /app/nexaBudget-be \
    && rm -rf target

# Stage 4: Immagine nativa minimale
FROM alpine:latest AS native
RUN apk add --no-cache libc6-compat
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=builder-native /app/nexaBudget-be .
EXPOSE 8080
ENTRYPOINT ["./nexaBudget-be"]
