plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")            // generates a no-arg constructor for @Entity classes
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
    // Web (REST controllers) + JSON
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Actuator — /actuator/health plus the liveness & readiness groups Cloud Run probes use.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Cloud SQL connector. Only used in the cloud, where DATABASE_URL names it as the JDBC
    // socketFactory — the driver loads it by name, so runtimeOnly is enough. Local dev connects
    // straight to the compose Postgres over TCP and never touches it.
    runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.28.4")

    // Request validation (@Valid, @Email, ...)
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Testing (JUnit 5, Mockito, MockMvc) — no Docker required
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
    // Same reason: bootRun should also see the repo-root .env.
    workingDir = rootProject.projectDir
}
