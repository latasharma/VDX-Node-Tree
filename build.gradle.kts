// The Android Gradle plugin goes on the root classpath only where an Android
// SDK is configured (see settings.gradle.kts), so the shared core and CLI
// build on machines without the SDK or Google's Maven repository.
buildscript {
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    repositories {
        mavenCentral()
        if (hasAndroidSdk) google()
    }
    dependencies {
        if (hasAndroidSdk) classpath("com.android.tools.build:gradle:8.10.1")
    }
}

plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("jvm") version "2.4.20" apply false
    kotlin("android") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
}
