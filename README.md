# kmp-socketio

KMP (pure Kotlin) implementation of SocketIO client.

![Maven Central Version](https://img.shields.io/maven-central/v/com.piasy/kmp-socketio) ![Main branch status](https://github.com/HackWebRTC/kmp-socketio/actions/workflows/ci.yaml/badge.svg?branch=main) ![Coverage](https://hackwebrtc.github.io/kmp-socketio/badges.svg)

## Supported platforms

|      Platform      | 🛠Builds🛠 + 🔬Tests🔬 |
| :----------------: | :------------------: |
|      `JVM` 17      |          🚀          |
| `JS`     (Chrome)  |          🚀          |
|     `Android`      |          🚀          |
|       `iOS`        |          🚀          |
|      `macOS`       |          🚀          |
|   `Windows X64`    |          🚀          |
|    `Linux X64`     |          🚀          |

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

        val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
        socket.emit("echo", 1, "2", bin, GMTDate())
    }.on("echoBack") { args ->
        println("on echoBack ${args.joinToString()}")
    }

    socket.open()
}
```

Most of the APIs are the same as socket.io-client-java, here are some differences:

- Create socket is asynchronous, to make it's easier to guarantee thread safety.
- Binary messages can't be nested, because `emit` only accepts String/Boolean/Number/JsonElement/ByteString, other types will be converted to String using `toString()`, so there is no way to put ByteString in JsonElement.

### Logging with [kmp-xlog](https://github.com/HackWebRTC/kmp-xlog)

## Development

To check coverage details, run `./gradlew :kmp-socketio:jvmTest --info && ./gradlew koverHtmlReport`,
then check `kmp-socketio/build/reports/kover/html/index.html`. 

## Example

Before running examples, run `node kmp-socketio/src/jvmTest/resources/socket-server.js` to start the socket-io echo server,
and update the local IP address in `example/shared/src/commonMain/kotlin/com/piasy/kmp/socketio/example/Greeting.kt`.

### Android

Open the project (the repo root dir) in Android studio, and run the example.androidApp target.

### iOS

```bash
brew install cocoapods xcodegen
# if you have installed them earlier, you need to remove them at first,
# or run brew link --overwrite xcodegen cocoapods

cd example/iosApp
xcodegen
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

### Linux

```bash
./gradlew runKmp_socketioDebugExecutableLinuxX64
```

### macOS

```bash
./gradlew runKmp_socketioDebugExecutableMacosX64
```

## Publish

Maven central portal credentials and signing configs are set in `~/.gradle/gradle.properties`.

```bash
# on Linux: need manual release on website
./gradlew clean publishLinuxX64PublicationToMavenCentralRepository --no-configuration-cache
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
