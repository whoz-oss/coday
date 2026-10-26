import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Factory Forge plugin — a PF4J plugin packaged as a standalone JAR.
 *
 * The plugin is compiled against the host Factory Service (and the Factory SDK it
 * exposes) but never bundles it: Spring, Jackson, PF4J, the SDK and the core
 * error/persistence types are all `compileOnly` and resolved from the host
 * classloader at runtime (APD strategy configured in factory-service).
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    `maven-publish`
}

group = "whoz-oss.factory"
version = libs.versions.factoryService.get()
description = "Factory Forge plugin — PF4J plugin exposing the Forge/BMAD and Jira surfaces"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get()))
    }
}

dependencies {
    // Spring Boot BOM so the host-provided libraries resolve without a version.
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))

    // Host + SDK are provided by the service classloader at runtime.
    compileOnly("whoz-oss.factory:factory-service:${libs.versions.factoryService.get()}")
    compileOnly("whoz-oss.factory:factory-sdk:${libs.versions.factorySdk.get()}")

    // Host-provided libraries: compileOnly so the plugin never bundles them, but
    // they must be visible to kapt/javac when generating the PF4J extensions.idx.
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.jackson.module.kotlin)

    // PF4J — plugin framework + annotation processor (`META-INF/extensions.idx`).
    compileOnly(libs.pf4j) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }
    // PF4J Spring integration (`SpringPlugin`, `SpringPluginManager`).
    compileOnly(libs.pf4j.spring) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }
    kapt(libs.pf4j) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }

    // Tests: the moved Forge tests are pure/direct (no Spring context, no DB).
    // `FactoryForgePluginIntegrationTest` additionally boots the host
    // factory-service (H2-backed `openapi` profile) with the plugin deployed.
    // The host runtime plus the standard test stack is enough for both.
    testImplementation("whoz-oss.factory:factory-service:${libs.versions.factoryService.get()}")
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// plugin.properties deliberately carries no `plugin.version` (same as the AgentOS plugins):
// the version is stamped in the JAR manifest below, which follows the project version.
// No resource templating, hence nothing at execution time touching `project`.
tasks.named<Jar>("jar") {
    archiveBaseName.set("factory-forge-plugin")
    manifest {
        attributes(
            "Plugin-Id" to "factory-forge-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "whoz-oss",
            "Plugin-Class" to "io.whozoss.factory.forge.plugin.ForgePlugin",
            "Plugin-Dependencies" to "",
        )
    }
}

// The host-boot integration test (`FactoryForgePluginIntegrationTest`) deploys the
// freshly built plugin JAR into a temp `plugins/` directory and starts the full
// factory-service application, so the JAR must exist first and its path is handed
// to the test through a system property.
val pluginJar = tasks.named<Jar>("jar")
// Resolve the plugin JAR path at configuration time so the configuration cache
// can serialize it as a plain String. Capturing pluginJar (a TaskProvider) or
// archiveFile (a Provider<RegularFile>) inside a doFirst closure would hold a
// reference to a Gradle script object, which the configuration cache cannot
// serialize and would fail with:
//   "configuration cache cannot serialize Gradle script object references".
val pluginJarPath: String = pluginJar.get().archiveFile.get().asFile.absolutePath
tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(pluginJar)
    // A test-time Java agent appends to the bootstrap classpath, which makes the
    // JDK emit a class-data-sharing warning on the test JVM. Disabling CDS for
    // tests removes the noise without affecting test behaviour.
    jvmArgs("-Xshare:off")
    systemProperty("factory.forge.plugin.jar", pluginJarPath)
}

/**
 * Installs the plugin JAR into Factory's sole PF4J runtime directory.
 * `build/libs/` remains build output only; runtime artifacts live in
 * `factory/plugins/`, matching the AgentOS deployment layout.
 */
val pluginsDir = layout.projectDirectory.dir("../plugins")

tasks.register<Copy>("deployPlugin") {
    group = "distribution"
    description = "Copies the built plugin JAR into the host plugins directory."
    dependsOn(tasks.named("jar"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(pluginsDir)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("jar"))

            pom {
                name.set("Factory Forge Plugin")
                description.set("Factory Forge plugin — PF4J plugin exposing the Forge/BMAD and Jira surfaces")
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
