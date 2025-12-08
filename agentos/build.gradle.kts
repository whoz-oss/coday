plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.biznet"
version = "0.0.1-SNAPSHOT"
description = "Agent OS Backend Service"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.0.3"
extra["pf4jVersion"] = "3.13.0"
extra["pf4jSpringVersion"] = "0.10.0"

dependencies {
    // Plugin API (published for external plugin developers)
    api(project(":plugin-api"))
    
    // PF4J Plugin Framework
    implementation("org.pf4j:pf4j:${property("pf4jVersion")}") {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }
    implementation("org.pf4j:pf4j-spring:${property("pf4jSpringVersion")}") {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // DevTools causes classloader issues with PF4J plugins
    // developmentOnly("org.springframework.boot:spring-boot-devtools")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
