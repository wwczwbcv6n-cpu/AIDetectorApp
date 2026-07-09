plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.native.cocoapods")
    kotlin("plugin.serialization") version "1.9.22" // Add serialization plugin
}

kotlin {
    androidTarget()
    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        val ktorVersion = "2.3.7" // Define Ktor version
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.components.resources)

                // Ktor for networking
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

                // Kotlinx Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
            }
        }
        val androidMain by getting {
            dependencies {
                api("androidx.activity:activity-compose:1.7.2")
                api("androidx.appcompat:appcompat:1.6.1")
                api("androidx.core:core-ktx:1.10.1")

                // Ktor Android engine
                implementation("io.ktor:ktor-client-okhttp:$ktorVersion")

                // Security/Encryption
                implementation("androidx.security:security-crypto:1.1.0-alpha06")

                // NOTE: org.pytorch:pytorch_android deliberately REMOVED — the
                // on-device model path was never invoked, yet its native libs
                // (4 ABIs) + bundled .ptl asset were ~350 MB of the APK. The
                // Android PyTorchModel actual is a stub (see PyTorchModel.kt);
                // sprint-2 on-device inference will use ONNX Runtime / LiteRT.
            }
        }
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                // Ktor iOS engine
                implementation("io.ktor:ktor-client-darwin:$ktorVersion")
                // PyTorch Lite — only iOS uses this; Android has its own
                // org.pytorch:pytorch_android dep, desktop uses the heuristic
                // path via the local stub (no native PyTorch on desktop).
                implementation("de.voize:pytorch-lite-multiplatform:0.7.0")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                // Dispatchers.Main on JVM/Compose Desktop is backed by Swing.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
                // Ktor JVM engine — Android has OkHttp, iOS has Darwin, desktop needs CIO.
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
            }
        }
    }

    cocoapods {
        summary = "AI Detector Shared Module"
        homepage = "https://example.com/aidetection" // Placeholder
        ios.deploymentTarget = "14.1" // Or your desired iOS deployment target
        framework {
            baseName = "shared"
            isStatic = true
        }
        pod("PLMLibTorchWrapper") {
            version = "0.7.0"
            headers = "LibTorchWrapper.h"
        }
    }
}

android {
    compileSdk = (findProperty("android.compileSdk") as String).toInt()
    namespace = "com.myapplication.common"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        minSdk = (findProperty("android.minSdk") as String).toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}
