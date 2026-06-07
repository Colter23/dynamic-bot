plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "dynamic-bot"
includeBuild("../dynamic-bot-core")

