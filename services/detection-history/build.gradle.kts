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

    // Actuator -- /actuator/health plus the liveness & readiness groups Cloud Run probes use.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // jOOQ -- typed SQL for the reporting queries, which is this service's whole point. The
    // starter also pulls spring-boot-starter-jdbc, so there is no separate JDBC dependency.
    // Used only when history.store=postgres; the default in-memory store needs no DB.
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Cloud SQL from Cloud Run: named as the JDBC socketFactory in DATABASE_URL, so connecting
    // needs no password over TCP and no IP allowlist. Same version the other services use.
    runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.28.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// -------------------------------------------------------------------------------------------
// jOOQ code generation -- from the Flyway SQL, with no database running.
//
// jOOQ normally reverse-engineers a live schema, which would mean "start Postgres before you can
// compile" (and on this machine WSL2 kills containers between shells). DDLDatabase avoids that:
// it replays src/main/resources/db/migration/*.sql into an in-memory H2 database and generates
// from that. The migrations stay the single source of truth, and a column renamed in SQL breaks
// the Kotlin at COMPILE time instead of at runtime -- the main thing jOOQ buys over JdbcTemplate.
//
// Done with a plain JavaExec rather than a jOOQ Gradle plugin deliberately: one fewer plugin to
// resolve and version, and the whole mechanism stays visible right here.
// -------------------------------------------------------------------------------------------

// jooq-meta-extensions (which has DDLDatabase) is NOT in Spring Boot's BOM, so it needs an
// explicit version -- and it must match the jOOQ the BOM manages, or generation emits code
// against a different API than the one on the runtime classpath. A mismatch surfaces as a
// compile error in this module, not as a surprise in production.
val jooqVersion = "3.19.35" // == spring-boot-dependencies 3.5.16's managed jooq.version

val jooqCodegen: Configuration by configurations.creating

dependencies {
    jooqCodegen("org.jooq:jooq-codegen:$jooqVersion")
    jooqCodegen("org.jooq:jooq-meta-extensions:$jooqVersion")
    // The in-memory database DDLDatabase replays the migrations into. Declared explicitly rather
    // than relied on transitively, because it is load-bearing here.
    jooqCodegen("com.h2database:h2:2.3.232")
}

val generatedJooqDir = layout.buildDirectory.dir("generated/jooq")

val generateJooq = tasks.register<JavaExec>("generateJooq") {
    group = "build"
    description = "Generates jOOQ classes from the Flyway migrations (via H2, no live database)"

    classpath = jooqCodegen
    mainClass = "org.jooq.codegen.GenerationTool"
    args("src/jooq/codegen.xml")
    // Paths inside codegen.xml are relative to this.
    workingDir = projectDir

    // Re-run only when the migrations or the codegen config change.
    inputs.dir("src/main/resources/db/migration")
    inputs.file("src/jooq/codegen.xml")
    outputs.dir(generatedJooqDir)
}

sourceSets["main"].java.srcDir(generatedJooqDir)

// The Kotlin here references the generated tables, so generation has to happen first. Kotlin
// compiles ahead of Java and resolves the generated Java from source, so this one dependency
// orders the whole chain.
tasks.named("compileKotlin") {
    dependsOn(generateJooq)
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
