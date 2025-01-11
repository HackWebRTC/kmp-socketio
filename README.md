# kmp-socketio

KMP (pure Kotlin) implementation of SocketIO client.

![Maven Central Version](https://img.shields.io/maven-central/v/com.piasy/kmp-socketio) ![Main branch status](https://github.com/HackWebRTC/kmp-socketio/actions/workflows/ci.yaml/badge.svg?branch=main) ![Coverage](https://hackwebrtc.github.io/kmp-socketio/badges.svg)

## Supported platforms

|      Platform      | 🛠Builds🛠 + 🔬Tests🔬 |
| :----------------: | :------------------: |
|      `JVM` 17      |          🚀          |
| `Browser` (Chrome) |          🚀          |
|     `Android`      |          🚀          |
|       `iOS`        |          🚀          |
|      `macOS`       |          🚀          |
|   `Windows X64`    |          🚀          |
|    `Linux X64`     |          🔮          |

About Linux support: Ktor's curl engine doesn't support websockets now,
although CIO engine supports websockets, but it doesn't support TLS.

Ref:

- [Native Sockets TLS Client/Server support for linux](https://github.com/ktorio/ktor/pull/2939)
- [Possible websockets support for curl engine](https://github.com/whyoleg/ktor/tree/libcurl-ws)

## Dependency

You only need to add gradle dependency:

```kotlin
// add common source set dependency
kotlin {
  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation("com.piasy:kmp-socketio:$version")
      }
    }
  }
}
```

## Usage

```kotlin
IO.socket("http://localhost:3000", IO.Options()) { socket ->
    socket.on(Socket.EVENT_CONNECT) { args ->
        println("on connect ${args.joinToString()}")

        socket.emit("echo", 1, "2", GMTDate())
    }.on("echoBack") { args ->
        println("on echoBack ${args.joinToString()}")
    }

    socket.open()
}
```

Most of the APIs are the same as socket.io-client-java, here are some differences:

- Create socket is asyncronous, to make it's easier to guarantee thread safety.
- Binary messages can't be nested, because `emit` only accepts String/Boolean/Number/JsonElement/ByteArray, other types will be converted to String using `toString()`, so there is no way to put ByteArray in JsonElement.

## Example

### Android

Open the project (the repo root dir) in Android studio, and run the example.androidApp target.

### iOS

```bash
cd example/iosApp
pod install
# open iosApp.xcworkspace in Xcode, and run it.
```

### JS

Use Chrome CORS Unblock extension to workaround with CORS error.

```bash
./gradlew :example:shared:jsBrowserRun
```

### Windows

```bash
.\gradlew runKmp_socketioDebugExecutableMingwX64
```

### macOS

```bash
./gradlew runKmp_socketioDebugExecutableMacosX64
```

## Publish

Maven central portal credentials and signing configs are set in `~/.gradle/gradle.properties`.

```bash
# on Windows: need manual release on website
.\gradlew clean publishMingwX64PublicationToMavenCentralRepository --no-configuration-cache
# on macOS: need manual release on website
./gradlew clean \
    publishKotlinMultiplatformPublicationToMavenCentralRepository \
    publishJvmPublicationToMavenCentralRepository \
    publishIosArm64PublicationToMavenCentralRepository \
    publishIosSimulatorArm64PublicationToMavenCentralRepository \
    publishIosX64PublicationToMavenCentralRepository \
    publishMacosArm64PublicationToMavenCentralRepository \
    publishMacosX64PublicationToMavenCentralRepository \
    publishJsPublicationToMavenCentralRepository \
    --no-configuration-cache
```

Login to https://central.sonatype.com/publishing/deployments, and release them manually.

## Credit

- [joffrey-bion/socketio-kotlin](https://github.com/joffrey-bion/socketio-kotlin)
- [dyte-io/socketio-kotlin](https://github.com/dyte-io/socketio-kotlin)
- [socketio/socket.io-client-java](https://github.com/socketio/socket.io-client-java)
- [socketio/engine.io-client-java](https://github.com/socketio/engine.io-client-java)
- [socketio/socket.io](https://github.com/socketio/socket.io)
- [ktorio/ktor](https://github.com/ktorio/ktor)
