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

// Plugin builds are identified by convention: their name must contain "plugin".
// This is more robust than a blacklist approach, which requires manual updates
// whenever a non-plugin included build is added.
val pluginBuilds: List<String> = gradle.includedBuilds
    .map { it.name }
    .filter { it.contains("plugin") }

// Resolve all paths at configuration time into plain File values
// so that doLast closures are configuration-cache compatible.
val pluginsDestDir: File = layout.projectDirectory.dir("plugins").asFile
val pluginLibsDirs: List<File> = pluginBuilds.map { gradle.includedBuild(it).projectDir.resolve("build/libs") }

tasks.register("cleanPlugins") {
    group = "plugins"
    description = "Cleans build outputs of all plugins."
    dependsOn(pluginBuilds.map { gradle.includedBuild(it).task(":clean") })
}

tasks.register("jarPlugins") {
    group = "plugins"
    description = "Builds JARs for all plugins after cleaning."
    dependsOn("cleanPlugins")
    dependsOn(pluginBuilds.map { gradle.includedBuild(it).task(":jar") })
}

/**
 * Cleans all plugin builds, rebuilds their JARs, and copies them into the plugins/ directory.
 *
 * Only the most recently built JAR from each plugin's build/libs/ is copied.
 * Stale JARs from previous builds with a different version that may linger in
 * build/libs/ are ignored, preventing PF4J duplicate pluginId errors at startup.
 *
 * Usage:
 *   ./gradlew deployPlugins
 */
tasks.register("deployPlugins") {
    group = "plugins"
    description = "Builds all plugins and copies their JARs into the plugins/ directory."

    // Clean each plugin build first (removes stale JARs from previous branch checkouts),
    // then build the JAR. The cleanPlugins task runs first, jarPlugins depends on it.
    dependsOn("jarPlugins")

    // Capture as local vals -- doLast must not reference project or gradle objects
    val dest = pluginsDestDir
    val libsDirs = pluginLibsDirs

    doLast {
        // Clean the plugins directory before deploying to avoid stale JARs
        // from previous builds (e.g. after a branch switch with a different version).
        if (dest.exists()) {
            dest.listFiles { f -> f.extension == "jar" }?.forEach { it.delete() }
        }
        dest.mkdirs()

        libsDirs.forEach { buildLibsDir ->
            // The :jar task always produces exactly one non-plain JAR.
            // If build/libs/ contains multiple JARs (stale versions from prior builds),
            // pick only the most recently modified one -- that is the one :jar just produced.
            val jars = buildLibsDir
                .listFiles { f -> f.extension == "jar" && !f.name.endsWith("-plain.jar") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (jars.isEmpty()) {
                logger.warn("No JAR found in $buildLibsDir")
            } else {
                val jar = jars.first()
                jar.copyTo(dest.resolve(jar.name), overwrite = true)
                logger.lifecycle("Deployed: ${jar.name} -> plugins/")
                if (jars.size > 1) {
                    logger.warn(
                        "${jars.size - 1} stale JAR(s) ignored in $buildLibsDir: " +
                            jars.drop(1).joinToString { it.name }
                    )
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
