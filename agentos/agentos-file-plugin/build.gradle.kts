plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt) // Required for PF4J annotation processing
    `maven-publish`
}

group = "whoz-oss.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS file plugin - provides file system tools"

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
                name.set("AgentOS File Plugin")
                description.set("AgentOS file plugin - provides file system tools")
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

    // Kotlin coroutines for async file operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // PDFBox for PDF page rendering (readAsImage tool) — bundled into the plugin jar,
    // the service classpath does not provide it
    implementation(libs.pdfbox)

    // POI for PPTX slide rendering (readAsImage tool) — bundled like PDFBox. Transitives
    // (xmlbeans, poi-ooxml-lite, commons-*, curvesapi, log4j-api) are all Apache-2.0/BSD.
    // log4j-api is also on the service classpath (log4j-to-slf4j) at the same version, so
    // the APD parent-first copy wins harmlessly and POI logs route to Logback.
    implementation(libs.poi.ooxml)

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
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Bundle PDFBox and POI with their transitive deps (fontbox, pdfbox-io, commons-logging,
    // xmlbeans, poi-ooxml-lite, commons-compress/io/codec/collections4/math3, curvesapi) into
    // the plugin JAR so the plugin classloader can resolve them independently of the service
    // classpath (PF4J APD strategy). Jackson and the SDK are declared compileOnly and
    // therefore NOT in runtimeClasspath -- they are provided by the service classloader
    // at runtime. Same pattern as agentos-mcp-plugin; the descriptor stays in
    // src/main/resources/plugin.properties (no manifest attributes here).
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
