plugins {
  id("dev.nx.gradle.project-graph") version ("0.1.10")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.kapt)
  `maven-publish`
}

group = "whoz-oss.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS Factory integration PF4J plugin"

java {
  toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt()) }
  targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
  withSourcesJar()
}

dependencies {
  implementation(libs.klogger)
  compileOnly("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")
  compileOnly(libs.pf4j)
  kapt(libs.pf4j)
  compileOnly(libs.bundles.jackson)
  compileOnly(libs.okhttp)

  testImplementation("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")
  testImplementation(libs.bundles.jackson)
  testImplementation(libs.okhttp)
  testImplementation(libs.bundles.testing.common)
  testImplementation(libs.pf4j)
  testRuntimeOnly(libs.junit.platform.launcher)
  kaptTest(libs.pf4j)
}

kapt {
  arguments { arg("pf4j.storageClassName", "org.pf4j.processor.LegacyExtensionStorage") }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict")
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get()))
  }
}

tasks.jar {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest {
    attributes(
      "Plugin-Id" to "agentos-factory-bridge-plugin",
      "Plugin-Version" to version,
      "Plugin-Provider" to "whoz-oss",
      "Plugin-Class" to "io.whozoss.agentos.plugins.factorybridge.FactoryBridgePlugin",
    )
  }
  from(configurations.runtimeClasspath.map { files -> files.filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "kotlin/**", "kotlinx/**")
  }
}

tasks.withType<Test> { useJUnitPlatform() }

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])

      pom {
        name.set("AgentOS Factory Bridge Plugin")
        description.set(project.description)
        url.set("https://github.com/whoz-oss/coday")

        licenses {
          license {
            name.set("Apache License 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0")
          }
        }

        developers {
          developer {
            id.set("whoz-oss")
            name.set("Whoz OSS")
            email.set("oss@whoz.com")
          }
        }

        scm {
          connection.set("scm:git:git://github.com/whoz-oss/coday.git")
          developerConnection.set("scm:git:ssh://github.com/whoz-oss/coday.git")
          url.set("https://github.com/whoz-oss/coday")
        }
      }
    }
  }

  repositories {
    mavenLocal()

    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/whoz-oss/coday")
      credentials {
        username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
        password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
      }
    }
  }
}

allprojects { apply(plugin = "dev.nx.gradle.project-graph") }
