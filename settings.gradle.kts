rootProject.name = "wildalert"

// One Gradle module per microservice. More services get added here as we build them
// (email-ingestion, notification, ...). The recognition service is Python and lives
// outside the Gradle build.
include("services:user-account")
