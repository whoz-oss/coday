rootProject.name = "agentos"


// Enable version catalog
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")


includeBuild("agentos-sdk")
includeBuild("agentos-service")
includeBuild("agentos-plugins-filesystem")
includeBuild("agentos-datetime-plugin")
includeBuild("agentos-file-plugin")
includeBuild("agentos-bash-plugin")