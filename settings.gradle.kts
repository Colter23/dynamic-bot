plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "dynamic-bot"

val localCore = file("../dynamic-bot-core")
if (localCore.isDirectory) {
    includeBuild(localCore)
}

