# Email-ingestion service image (same template as user-account).
# Build context is the repo root:  docker build -f services/email-ingestion/Dockerfile .

# --- Build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :services:email-ingestion:bootJar --no-daemon

# --- Run stage ---
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/services/email-ingestion/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
