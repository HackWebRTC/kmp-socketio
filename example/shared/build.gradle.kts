@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kmp)

    alias(libs.plugins.android.library)
    // alias will fail, see https://github.com/gradle/gradle/issues/20084
    id("org.jetbrains.kotlin.native.cocoapods")
}

kotlin {
    androidTarget()

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64 {
        binaries {
            executable("kmp_socketio") {
                entryPoint = "com.piasy.kmp.socketio.example.main"
            }
        }
    }

    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = Consts.releaseVersion
        ios.deploymentTarget = libs.versions.iosDeploymentTarget.get()
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "shared"
            isStatic = true
        }
    }

    js {
        browser()
        nodejs()
        binaries.executable()
    }
    wasmJs {
        browser()
        nodejs()
        d8()
        binaries.executable()
    }
    // ktor doesn't support wasmWasi yet.
//    wasmWasi {
//        nodejs()
//        binaries.executable()
//    }

    listOf(linuxX64(), mingwX64()).forEach {
        it.binaries {
            executable("kmp_socketio") {
                entryPoint = "com.piasy.kmp.socketio.example.main"
            }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain {
            dependencies {
                //implementation("${Consts.releaseGroup}:${Consts.releaseName}:${Consts.releaseVersion}")
                implementation(project(":kmp-socketio"))
            }
        }
    }
}

android {
    namespace = "${Consts.androidNS}.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvm.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.get().toInt())
    }

    kotlin {
        jvmToolchain(libs.versions.jvm.get().toInt())
    }
}
