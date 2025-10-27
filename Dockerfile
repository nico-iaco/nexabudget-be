# =================================================================================
# Stage 1: Builder per il JAR standard (JVM)
# =================================================================================
FROM maven:3.9-eclipse-temurin-21 AS builder-jvm
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# =================================================================================
# Stage 2: Immagine finale per la JVM (Target: jvm)
# =================================================================================
FROM eclipse-temurin:21-jre-jammy AS jvm
WORKDIR /app
COPY --from=builder-jvm /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# =================================================================================
# Stage 3: Builder per l'eseguibile nativo
# =================================================================================
FROM vegardit/graalvm-maven:latest-java21 AS builder-native
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Usa il profilo 'native' per attivare la compilazione nativa
RUN mvn -Pnative clean package -DskipTests

# =================================================================================
# Stage 4: Immagine finale nativa (Target: native)
# =================================================================================
FROM oraclelinux:9-slim AS native
WORKDIR /app
# Copia l'eseguibile nativo dalla fase di build
COPY --from=builder-native /app/target/nexaBudget-be .
EXPOSE 8080
ENTRYPOINT ["./nexaBudget-be"]
