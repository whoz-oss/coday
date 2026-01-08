rootProject.name = "agentos"


// Enable version catalog
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")


includeBuild("agentos-sdk")
includeBuild("agentos-service")

// Include API modules
include("plugin-api")

// Include client modules
//include("copilot-client")

// Include plugin projects
include("code-based-plugin")
include("filesystem-plugin")
//include("example-plugin")
//include("copilot-agents-plugin")
