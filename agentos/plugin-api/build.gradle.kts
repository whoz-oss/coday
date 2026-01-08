plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "io.biznet.agentos"
version = "1.0.0"
description = "AgentOS Plugin API - Extension point interfaces for AgentOS plugins"

repositories {
    mavenCentral()
}

dependencies {
    // PF4J for ExtensionPoint
    api("org.pf4j:pf4j:3.13.0")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            pom {
                name.set("AgentOS Plugin API")
                description.set("Extension point interfaces and models for developing AgentOS plugins")
                url.set("https://github.com/biznet-io/agentos")
                
                licenses {
                    license {
                        name.set("Proprietary")
                        url.set("https://github.com/biznet-io/agentos/blob/main/LICENSE")
                    }
                }
                
                developers {
                    developer {
                        id.set("biznet")
                        name.set("Biznet.io")
                        email.set("contact@biznet.io")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/biznet-io/agentos.git")
                    developerConnection.set("scm:git:ssh://github.com/biznet-io/agentos.git")
                    url.set("https://github.com/biznet-io/agentos")
                }
            }
        }
    }
    
    repositories {
        // Local Maven repository for testing
        mavenLocal()
        
        // GitHub Packages (free and easy!)
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/biznet-io/agentos")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
