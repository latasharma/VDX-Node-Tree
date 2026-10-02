import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

// iOS targets need the Kotlin/Native toolchain, which only builds Apple
// frameworks on macOS. Declare them there and skip them elsewhere.
val isMac = System.getProperty("os.name").startsWith("Mac")

kotlin {
    // Android consumes this JVM target: the shared core is plain Kotlin, and
    // platform-specific code (contacts, dialer, speech) lives in the apps.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    if (isMac) {
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "NodeTreeShared"
                isStatic = true
            }
        }
    }

    sourceSets {
        all { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
