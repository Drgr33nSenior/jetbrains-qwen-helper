plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}
group = "family.thespry"
version = "0.1.0"
repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
dependencies {
    intellijPlatform { pycharm("2026.2.4"); pluginVerifier() }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
kotlin { jvmToolchain(25); compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25 } }
java { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
tasks.test { useJUnitPlatform() }
intellijPlatform {
    pluginConfiguration {
        name = "Qwen Workstation"
        ideaVersion { sinceBuild = "262"; untilBuild = "262.*" }
    }
    pluginVerification { ides { create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.PyCharm, "2026.2.4") } }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
}
