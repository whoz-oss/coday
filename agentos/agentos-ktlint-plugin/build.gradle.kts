plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt) // Required for PF4J annotation processing
    `maven-publish`
}

group = "whoz-oss.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS ktlint plugin - exposes ktlint check and format operations as tools"

java {
    toolchain {
        languageVersion =
            JavaLanguageVersion.of(
                libs.versions.java
                    .get()
                    .toInt(),
            )
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("AgentOS ktlint Plugin")
                description.set("AgentOS ktlint plugin - exposes ktlint check and format operations as tools")
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

dependencies {
    implementation(libs.klogger)

    // ktlint embedded engine and standard ruleset (ktlint_check / ktlint_format) — bundled into the plugin jar,
    // the service classpath does not provide them. Pulls kotlin-compiler-embeddable (heavy, see the jar task).
    implementation(libs.ktlint.rule.engine)
    implementation(libs.ktlint.ruleset.standard)

    // AgentOS SDK - Contains plugin interfaces
    compileOnly("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")

    // Coroutines (runInterruptible in the tools) are exposed by the SDK and provided by the service classloader at runtime, never bundled
    compileOnly(libs.kotlinx.coroutines.core)

    // PF4J - Required for @Extension annotation processing
    compileOnly(libs.pf4j)
    kapt(libs.pf4j)

    // Jackson for JSON handling (shared with the service classloader, never bundled)
    compileOnly(libs.bundles.jackson)

    // Testing
    testImplementation("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.bundles.jackson)
    testImplementation(libs.bundles.testing.common)
    testImplementation(libs.pf4j)
    testRuntimeOnly(libs.junit.platform.launcher)
    kaptTest(libs.pf4j)
}

// Configure kapt for PF4J extension processing
kapt {
    arguments {
        arg("pf4j.storageClassName", "org.pf4j.processor.LegacyExtensionStorage")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(libs.versions.kotlinJvmTarget.get()),
        )
    }
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "agentos-ktlint-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "whoz-oss",
            "Plugin-Class" to "io.whozoss.agentos.plugins.ktlint.KtlintPlugin",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Bundle ktlint (rule engine, standard ruleset, kotlin-compiler-embeddable and their transitive deps) into the
    // plugin JAR so the plugin classloader resolves them independently of the service classpath (PF4J APD strategy).
    // Jackson and the SDK are declared compileOnly and therefore NOT in runtimeClasspath: the service provides them.
    // The Kotlin stdlib/reflect (kotlin/**) and coroutines (kotlinx/**) come from the service; the embedded compiler
    // lives under org/jetbrains/kotlin/** and is bundled. Same pattern as agentos-file-plugin.
    from(configurations.runtimeClasspath.map { fc -> fc.filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("kotlin/**", "kotlinx/**")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}
