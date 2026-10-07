# Notification service image (same template as user-account).
# Build context is the repo root:  docker build -f services/notification/Dockerfile .

# --- Build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
# chmod: gradlew is committed without the execute bit (it was added from Windows), so
# ./gradlew would be "Permission denied" in the build image.
RUN chmod +x gradlew && ./gradlew :services:notification:bootJar --no-daemon

# --- Run stage ---
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/services/notification/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
