plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.wildalert"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Actuator — /actuator/health plus the liveness & readiness groups Cloud Run probes use.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Twilio SMS provider (used only when sms.provider=twilio)
    implementation("com.twilio.sdk:twilio:13.0.0")

    // Postgres — remembers handled events so a redelivery can't send a second SMS
    // (only when idempotency.store=postgres; the default in-memory store needs no DB)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Cloud SQL from Cloud Run: named as the JDBC socketFactory in DATABASE_URL, so connecting
    // needs no password over TCP and no IP allowlist. Same version user-account uses.
    runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.28.4")

    // Mints the Google ID token our calls to a private user-account need (GcpIdTokenUserAccountAuth).
    // email-ingestion gets this transitively from the Pub/Sub starter; this service has no other GCP
    // dependency — a push subscription is just an HTTP POST — so it is declared here. The BOM is
    // metadata only and keeps the version aligned with email-ingestion's (1.47.0 today).
    implementation(platform("com.google.cloud:spring-cloud-gcp-dependencies:7.4.9"))
    implementation("com.google.auth:google-auth-library-oauth2-http")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Run from the repo root so Spring finds the shared .env there.
    workingDir = rootProject.projectDir
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    workingDir = rootProject.projectDir
}
