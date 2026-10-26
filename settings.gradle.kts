rootProject.name = "wildalert"

// One Gradle module per microservice. More services get added here as we build them.
// The recognition service is Python and lives outside the Gradle build.
include("services:user-account")
include("services:notification")
include("services:email-ingestion")
include("services:detection-history")
include("services:gateway")
