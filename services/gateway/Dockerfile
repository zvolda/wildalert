# Gateway image: the React app and the Kotlin gateway in one container.
# Build context is the repo root:  docker build -f services/gateway/Dockerfile .
#
# This is the only image here with two toolchains in it. The UI is built by Node and the result
# is copied into the Gradle module's resources *before* the jar is packaged, so the finished jar
# carries the static files inside it. One artifact, one deploy, and no way for the page and the
# API it calls to be different versions of each other.

# --- Stage 1: build the React app -----------------------------------------------------------
FROM node:22 AS web
WORKDIR /web

# The npm bundled with node:22 (10.9.9) crashes resolving this project's peer graph --
# `Cannot read properties of null (reading 'edgesOut')` from inside arborist. See AGENTS.md.
RUN npm install -g npm@latest

# Manifests first, so a change to application source does not invalidate the install layer.
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-fund --no-audit

COPY web/ ./
# Runs tsc --noEmit then vite build, so a type error fails the image build rather than shipping.
RUN npm run build

# --- Stage 2: build the jar, with the built UI inside it -------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .

# Spring serves classpath:/static/ -- see application.yml. The repo keeps this directory empty
# on purpose, so a failed copy shows up as a 404 rather than as a stale page.
COPY --from=web /web/dist services/gateway/src/main/resources/static

# chmod: gradlew is committed without the execute bit (added from Windows), so ./gradlew would
# be "Permission denied" in the build image.
RUN chmod +x gradlew && ./gradlew :services:gateway:bootJar --no-daemon

# --- Run stage ------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/services/gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
