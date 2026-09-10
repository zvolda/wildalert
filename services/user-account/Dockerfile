# Template Dockerfile for a Kotlin/Spring Boot service. Built in the cloud at deploy
# time (Cloud Run / Railway) — not required for local development.
# Build context is the repo root:  docker build -f services/user-account/Dockerfile .

# --- Build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :services:user-account:bootJar --no-daemon

# --- Run stage ---
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/services/user-account/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
