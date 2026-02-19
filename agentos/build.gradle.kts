// Root build file for AgentOS composite build
// Individual modules (agentos-sdk, agentos-service) are now composite builds
// with their own settings.gradle.kts and build configurations
plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

group = "whoz-oss.agentos"
version = "0.0.1-SNAPSHOT"

tasks.named("clean") {
    dependsOn(gradle.includedBuilds.map { it.task(":clean") })
}

tasks.named("build") {
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
}

// ========================================
// Plugin deployment
// ========================================

val pluginBuilds = listOf("agentos-plugins-filesystem", "agentos-datetime-plugin")

// Resolve all paths at configuration time into plain File values
// so that doLast closures are configuration-cache compatible.
val pluginsDestDir: File = layout.projectDirectory.dir("plugins").asFile
val pluginLibsDirs: List<File> = pluginBuilds.map { gradle.includedBuild(it).projectDir.resolve("build/libs") }

/**
 * Builds all plugins and copies their JARs into the plugins/ directory.
 *
 * Usage:
 *   ./gradlew deployPlugins
 */
tasks.register("deployPlugins") {
    group = "plugins"
    description = "Builds all plugins and copies their JARs into the plugins/ directory."

    dependsOn(pluginBuilds.map { gradle.includedBuild(it).task(":jar") })

    // Capture as local vals — doLast must not reference project or gradle objects
    val dest = pluginsDestDir
    val libsDirs = pluginLibsDirs

    doLast {
        dest.mkdirs()

        libsDirs.forEach { buildDir ->
            val jars = buildDir.listFiles { f -> f.extension == "jar" && !f.name.endsWith("-plain.jar") }
                ?: emptyArray()

            if (jars.isEmpty()) {
                logger.warn("No JAR found in $buildDir")
            } else {
                jars.forEach { jar ->
                    jar.copyTo(dest.resolve(jar.name), overwrite = true)
                    logger.lifecycle("Deployed: ${jar.name} → plugins/")
                }
            }
        }
    }
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}

