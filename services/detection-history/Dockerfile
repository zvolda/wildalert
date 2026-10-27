# Detection-history service image (same template as notification / user-account).
# Build context is the repo root:  docker build -f services/detection-history/Dockerfile .
#
# The build stage runs jOOQ code generation on the way to bootJar — `generateJooq` is wired as a
# dependency of compileKotlin, and it needs no database because DDLDatabase replays the Flyway
# migrations into in-memory H2. It does need the network, to fetch jooq-codegen and H2 themselves;
# Cloud Build has it. The root .dockerignore excludes **/build/, so nothing generated locally is
# sent — the image always generates from the migrations in this commit.

# --- Build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
# chmod: gradlew is committed without the execute bit (it was added from Windows), so
# ./gradlew would be "Permission denied" in the build image.
RUN chmod +x gradlew && ./gradlew :services:detection-history:bootJar --no-daemon

# --- Run stage ---
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/services/detection-history/build/libs/*.jar app.jar
# Documentation only, and the local port rather than the deployed one: Cloud Run injects PORT
# (8080 there) and application.yml honours it, falling back to 8086 locally.
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
