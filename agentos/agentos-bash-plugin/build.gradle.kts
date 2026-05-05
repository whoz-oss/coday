plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt) // Required for PF4J annotation processing
    `maven-publish`
}

group = "whoz-oss.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS bash plugin - exposes configured bash commands as tools"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("AgentOS Bash Plugin")
                description.set("AgentOS bash plugin - exposes configured bash commands as tools")
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

    // AgentOS SDK - Contains plugin interfaces
    compileOnly("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")

    // PF4J - Required for @Extension annotation processing
    compileOnly(libs.pf4j)
    kapt(libs.pf4j)

    // Jackson for JSON handling
    compileOnly(libs.bundles.jackson)

    // Testing
    testImplementation("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get()))
    }
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "agentos-bash-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "whoz-oss",
            "Plugin-Class" to "io.whozoss.agentos.plugins.bash.BashPlugin",
        )
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
