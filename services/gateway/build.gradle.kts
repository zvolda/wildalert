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

// Spring Cloud ships as a release train with its own version, independent of Boot's. 2025.0.x is
// the train that targets Boot 3.5.x -- the root build's version. Mismatching these is the usual
// cause of baffling NoSuchMethodErrors at startup, so it is pinned here next to a comment rather
// than left to drift.
val springCloudVersion = "2025.0.3"

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
    }
}

dependencies {
    // Spring Cloud Gateway, SERVLET flavour (the "mvc" one), not the reactive/WebFlux default.
    // Deliberate: every other service here is servlet-based Spring MVC, this one serves static
    // files (which is the plain-MVC path), and the ID-token code it reuses from notification is
    // written against RestClient -- which is exactly what Gateway MVC proxies with. Choosing the
    // reactive gateway would drag WebFlux and a different mental model in for no gain at this size.
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc")

    // Actuator -- /actuator/health plus the liveness & readiness groups Cloud Run probes use.
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Mints the Google ID token that the private user-account requires. Same library, same BOM
    // and the same reason as in notification; see UserAccountAuth. The version comes from the
    // GCP BOM rather than being pinned here, so the two services cannot drift apart.
    implementation(platform("com.google.cloud:spring-cloud-gcp-dependencies:7.4.9"))
    implementation("com.google.auth:google-auth-library-oauth2-http")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

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
