import org.gradle.api.file.DuplicatesStrategy
import org.gradle.kotlin.dsl.kotlin

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "top.colter.dynamic"
version = "0.0.2"

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

configurations.configureEach {
    resolutionStrategy.force("org.slf4j:slf4j-api:2.0.18")
}

fun currentSkikoRuntimeTarget(): String {
    val targetOs = when {
        System.getProperty("os.name") == "Mac OS X" -> "macos"
        System.getProperty("os.name").startsWith("Win") -> "windows"
        System.getProperty("os.name").startsWith("Linux") -> "linux"
        else -> error("Unsupported OS: ${System.getProperty("os.name")}")
    }

    val targetArch = when (System.getProperty("os.arch")) {
        "x86_64", "amd64" -> "x64"
        "aarch64" -> "arm64"
        else -> error("Unsupported arch: ${System.getProperty("os.arch")}")
    }
    return "$targetOs-$targetArch"
}

fun skikoRuntimeTargets(): Set<String> {
    val supportedTargets = setOf(
        "windows-x64",
        "linux-x64",
        "linux-arm64",
        "macos-x64",
        "macos-arm64",
    )
    val configured = providers.gradleProperty("skikoRuntimeTargets")
        .orNull
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }

    val targets = configured?.toCollection(linkedSetOf())
        ?: linkedSetOf(currentSkikoRuntimeTarget(), "windows-x64", "linux-x64")
    val unsupported = targets - supportedTargets
    require(unsupported.isEmpty()) {
        "Unsupported skikoRuntimeTargets: ${unsupported.joinToString()}. Supported: ${supportedTargets.joinToString()}"
    }
    return targets
}

dependencies {
    val coroutinesVersion = "1.11.0"
    val exposedVersion = "1.3.0"
    val jacksonVersion = "2.22.0"
    val skikoVersion = "0.148.1"
    val ktorVersion = "3.5.0"
    val log4jVersion = "2.26.0"
    val slf4jVersion = "2.0.18"

    implementation("top.colter.skiko:skiko-layout:0.0.6")
    implementation("org.jetbrains.skiko:skiko-awt:$skikoVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("top.colter.dynamic:dynamic-bot-core:0.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("ch.qos.logback:logback-classic:1.5.34")
    implementation("org.apache.logging.log4j:log4j-to-slf4j:$log4jVersion")
    implementation("org.slf4j:jul-to-slf4j:$slf4jVersion")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-migration-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")

    skikoRuntimeTargets().forEach { runtimeTarget ->
        runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$runtimeTarget:$skikoVersion")
    }
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "top.colter.dynamic.MainKt"
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds an executable fat jar with runtime dependencies."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "top.colter.dynamic.MainKt"
        attributes["Multi-Release"] = "true"
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    jvmToolchain(21)
}
