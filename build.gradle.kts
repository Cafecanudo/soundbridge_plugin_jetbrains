plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.soundbridge"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    version.set(providers.gradleProperty("platformVersion").get())
    type.set("IC")
    plugins.set(emptyList())
}

kotlin {
    jvmToolchain(17)
}

tasks {
    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild").get())
        untilBuild.set(providers.gradleProperty("pluginUntilBuild").get())
    }
}
