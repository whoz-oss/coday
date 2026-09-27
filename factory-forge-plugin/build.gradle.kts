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

group = "io.whozoss.factory"
version = "0.0.1-SNAPSHOT"
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
    compileOnly("io.whozoss.factory:factory-service:0.0.1-SNAPSHOT")
    compileOnly("io.whozoss.factory:factory-sdk:0.0.1-SNAPSHOT")

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
    testImplementation("io.whozoss.factory:factory-service:0.0.1-SNAPSHOT")
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("factory-forge-plugin")
    manifest {
        attributes(
            "Plugin-Id" to "factory-forge-plugin",
            "Plugin-Version" to project.version.toString(),
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
tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(pluginJar)
    // A test-time Java agent appends to the bootstrap classpath, which makes the
    // JDK emit a class-data-sharing warning on the test JVM. Disabling CDS for
    // tests removes the noise without affecting test behaviour.
    jvmArgs("-Xshare:off")
    doFirst {
        systemProperty("factory.forge.plugin.jar", pluginJar.get().archiveFile.get().asFile.absolutePath)
    }
}

/**
 * Assemblies the plugin JAR into the host drop-in directory so a running
 * factory-service loads it. The target directory defaults to
 * `factory-service/plugins/` and can be overridden with
 * `-Pfactory.plugins.dir=<dir>` (or the `plugins.dir` project property).
 */
val pluginsDir: Provider<String> =
    providers.gradleProperty("factory.plugins.dir")
        .orElse(providers.gradleProperty("plugins.dir"))
        .orElse(layout.projectDirectory.dir("../factory-service/plugins").asFile.absolutePath)

tasks.register<Copy>("deployPlugin") {
    group = "distribution"
    description = "Copies the built plugin JAR into the host plugins directory."
    dependsOn(tasks.named("jar"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(pluginsDir)
}
