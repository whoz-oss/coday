rootProject.name = "agentos-factory-bridge-plugin"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
  repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://jitpack.io") }
  }
}
