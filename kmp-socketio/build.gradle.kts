import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kmp)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kover)
}

version = Consts.releaseVersion
group = Consts.releaseGroup

kotlin {
    jvm {
        withJava()
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    js(IR) {
        browser {
        }
        binaries.executable()
    }
    mingwX64 {}
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "kmp-socketio"
        browser {
        }
        binaries.executable()
    }

    // Ktor's curl engine doesn't support websockets now,
    // although CIO engine supports websockets, but it doesn't support TLS.
    // - [Native Sockets TLS Client/Server support for linux](https://github.com/ktorio/ktor/pull/2939)
    // - [Possible websockets support for curl engine](https://github.com/whyoleg/ktor/tree/libcurl-ws)
    //linuxX64 {}

    applyDefaultHierarchyTemplate()
    sourceSets {
        all {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }

        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.socketioParser)
                api(libs.ktor.client.core)
                api(libs.ktor.client.logging)
                api(libs.ktor.client.websockets)
//                api(libs.kmpXlog)
            }
        }
        jvmMain {
            dependencies {
                //api(libs.ktor.client.java) // java engine can't get ws response headers
                //api(libs.ktor.client.okhttp) // okhttp engine can get ws response headers, but all in lowercase
                api(libs.ktor.client.cio) // cio engine works fine
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                // mockk isn't available for commonTest,
                // other options are:
                // https://github.com/mockative/mockative
                // https://github.com/kosi-libs/MocKMP
                // but only run tests on JVM seems fine.
                implementation(libs.mockk)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.junit)
                implementation(libs.hamcrest)
                implementation(libs.json)
            }
        }
        appleMain {
            dependencies {
                api(libs.ktor.client.darwin)
            }
        }
        jsMain {
            dependencies {
                api(libs.ktor.client.js)
            }
        }
        mingwMain {
            dependencies {
                api(libs.ktor.client.winhttp)
            }
        }
        wasmJsMain {
            dependencies {
                api(libs.ktor.client.wasm)
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates(group.toString(), Consts.releaseName, version.toString())

    pom {
        name = "kmp-socketio"
        description = "KMP implementation of SocketIO client."
        inceptionYear = "2024"
        url = "https://github.com/HackWebRTC/kmp-socketio"
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
                distribution = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "Piasy"
                name = "Piasy Xu"
                url = "xz4215@gmail.com"
            }
        }
        scm {
            url = "https://github.com/HackWebRTC/kmp-socketio"
            connection = "scm:git:git://github.com/HackWebRTC/kmp-socketio.git"
            developerConnection = "scm:git:git://github.com/HackWebRTC/kmp-socketio.git"
        }
    }
}
