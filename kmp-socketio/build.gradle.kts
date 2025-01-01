import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kmp)
    alias(libs.plugins.vanniktech.mavenPublish)

    alias(libs.plugins.android.library)
}

version = Consts.releaseVersion
group = Consts.releaseGroup

kotlin {
    jvm()

    androidTarget {
        publishLibraryVariants("release")
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
            }
        }
        jvmMain {
            dependencies {
                api(libs.ktor.client.java)
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
            }
        }
        androidMain {
            dependencies {
                api(libs.ktor.client.okhttp)
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
    }
}

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    namespace = Consts.androidNS

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

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates(group.toString(), Consts.releaseName, version.toString())

    pom {
        name = "kmp-socketio"
        description = "KMP implementation of SocketIO client."
        inceptionYear = "2022"
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
