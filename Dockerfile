# Stage 1: Builder con cache delle dipendenze
FROM maven:3.9-eclipse-temurin-25 AS builder-jvm
WORKDIR /app
# Copia solo il pom.xml per cachare le dipendenze
COPY pom.xml .
RUN mvn dependency:go-offline
# Copia il resto e compila
COPY src ./src
RUN mvn clean package -DskipTests

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
# Install Maven
# Install Maven by copying from builder-jvm (avoids microdnf segfault on QEMU)
COPY --from=builder-jvm /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
ENV PATH=${MAVEN_HOME}/bin:${PATH}
# Check if find exists, otherwise we might have issues, but let's try without installing it first

COPY pom.xml .
RUN mvn dependency:go-offline -Pnative
COPY src ./src
RUN mvn -Pnative clean package -DskipTests

# Stage 4: Immagine nativa minimale
FROM alpine:latest AS native
RUN apk add --no-cache libc6-compat
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=builder-native /app/target/nexaBudget-be .
EXPOSE 8080
ENTRYPOINT ["./nexaBudget-be"]

