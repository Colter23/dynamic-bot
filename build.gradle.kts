plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21" // 临时
}

group = "top.colter.dynamic"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

configurations.configureEach {
    resolutionStrategy.force("org.slf4j:slf4j-api:2.0.16")
}

dependencies {
    val skikoVersion = "0.8.23"
    val ktorVersion = "3.0.3"
    val log4jVersion = "2.25.4"

    implementation("top.colter.skiko:skiko-layout:0.0.4")
    implementation("org.jetbrains.skiko:skiko-awt:$skikoVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")// 临时

    implementation("top.colter.dynamic:dynamic-bot-core:0.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("org.apache.logging.log4j:log4j-to-slf4j:$log4jVersion")
    implementation("org.slf4j:jul-to-slf4j:2.0.16")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")
    val exposedVersion = "1.2.0"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-migration-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    val osName = System.getProperty("os.name")
    val targetOs = when {
        osName == "Mac OS X" -> "macos"
        osName.startsWith("Win") -> "windows"
        osName.startsWith("Linux") -> "linux"
        else -> error("Unsupported OS: $osName")
    }

    val osArch = System.getProperty("os.arch")
    val targetArch = when (osArch) {
        "x86_64", "amd64" -> "x64"
        "aarch64" -> "arm64"
        else -> error("Unsupported arch: $osArch")
    }
    val target = "${targetOs}-${targetArch}"
    linkedSetOf(target, "windows-x64", "linux-x64").forEach { runtimeTarget ->
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
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })
}

kotlin {
    jvmToolchain(21)
}
