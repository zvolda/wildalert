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
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Jakarta Mail (MimeMessage parsing) via the mail starter
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // AWS S3 SDK v2 — used to talk to Cloudflare R2 (S3-compatible), only when storage.provider=r2
    implementation(platform("software.amazon.awssdk:bom:2.31.0"))
    implementation("software.amazon.awssdk:s3")

    // Spring Cloud GCP Pub/Sub — publishes events when events.provider=pubsub.
    // The 7.x line is the one for Spring Boot 3.5; 8.x targets Boot 4.
    implementation(platform("com.google.cloud:spring-cloud-gcp-dependencies:7.4.9"))
    implementation("com.google.cloud:spring-cloud-gcp-starter-pubsub")

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
