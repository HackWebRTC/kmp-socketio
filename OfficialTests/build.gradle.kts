plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // test fixture not available for KMP project,
    // https://youtrack.jetbrains.com/issue/KT-63142/
    testImplementation(project(":kmp-socketio"))
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
