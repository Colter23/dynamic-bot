plugins {
    kotlin("jvm")
}

group = "top.colter.dynamic"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation("top.colter.skiko:skiko-layout:0.0.3")
    implementation("org.jetbrains.skiko:skiko-awt:0.8.23")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    implementation("com.google.zxing:javase:3.5.3")


    implementation(project(":dynamic-client"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.23")
//    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.7.27")
//    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:0.7.27")
//    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.7.27")
//    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.7.27")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
}