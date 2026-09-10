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
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

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
