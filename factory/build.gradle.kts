// Root build file for the Factory composite build.
// Individual modules (factory-sdk, factory-verification-core, factory-service,
// factory-forge-plugin) are composite builds with their own settings.gradle.kts
// and build configurations. This root file orchestrates them uniformly,
// mirroring the agentos/build.gradle.kts layout.
//
// The dev.nx.gradle.project-graph plugin is declared with an explicit version
// string. The `libs` catalog (factory/gradle/libs.versions.toml) is imported
// automatically by Gradle for this root build, which is how `version` below
// reads `factoryService`, exactly like agentos/build.gradle.kts reads its own.
plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    base
}

group = "whoz-oss.factory"
version = libs.versions.factoryService.get()

tasks.named("clean") {
    dependsOn(gradle.includedBuilds.map { it.task(":clean") })
}

tasks.named("build") {
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
}

// ========================================
// Plugin deployment
// ========================================
// Only factory-forge-plugin produces a deployable plugin JAR.
// Additional plugin modules can be added to this list as they are created.

val pluginBuilds =
    listOf(
        "factory-forge-plugin",
    )

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

tasks.register("deployPlugins") {
    group = "plugins"
    description = "Builds all plugins and copies their JARs into plugins/."

    // Clean each plugin build first (removes stale JARs from previous branch checkouts),
    // then build the JAR. The cleanPlugins task runs first, jarPlugins depends on it.
    dependsOn("jarPlugins")

    // Capture as local vals — doLast must not reference project or gradle objects
    val dest = pluginsDestDir
    val libsDirs = pluginLibsDirs

    doLast {
        dest.mkdirs()

        // Collect JARs to deploy (excluding *-plain.jar) from each plugin build dir.
        val jarsToDeploy: List<File> =
            libsDirs.flatMap { buildDir ->
                buildDir.listFiles { f -> f.extension == "jar" && !f.name.endsWith("-plain.jar") }?.toList()
                    ?: emptyList<File>().also { logger.warn("No JAR found in $buildDir") }
            }

        // Strict cleanup: plugins/ must contain exactly what was just built, nothing else.
        // Any other JAR (old version, plugin removed from the build, hand-copied file) would be
        // loaded by PF4J and make the running behaviour impossible to explain.
        dest.listFiles { f -> f.extension == "jar" }?.forEach { existing ->
            existing.delete()
            logger.lifecycle("Removed stale: ${existing.name}")
        }

        jarsToDeploy.forEach { jar ->
            jar.copyTo(dest.resolve(jar.name), overwrite = true)
            logger.lifecycle("Deployed: ${jar.name} → plugins/")
        }
    }
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}
