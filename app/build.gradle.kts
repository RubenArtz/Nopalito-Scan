/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

import java.util.*

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibrariesAndroid)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

val abiCodes = mapOf(
    "arm64-v8a" to 0,
    "armeabi-v7a" to -1,
    "x86_64" to -2,
)

fun readApiBaseUrl(): String {
    val properties = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { properties.load(it) }
    }
    return (findProperty("API_BASE_URL") as String?)?.takeIf { it.isNotBlank() }
        ?: properties.getProperty("API_BASE_URL")
        ?: System.getenv("API_BASE_URL")
        ?: throw GradleException(
            "API_BASE_URL is not configured. Pass -PAPI_BASE_URL=https://your-api.example.com/ " +
                    "on the command line, add 'API_BASE_URL=https://your-api.example.com/' " +
                    "to local.properties (git-ignored), or set the API_BASE_URL environment variable."
        )
}

android {
    namespace = "nopalito.app"
    compileSdk = 37
    // Assets from download-tflite are registered via androidComponents below

    defaultConfig {
        applicationId = "nopalito.app"
        // Based on tests against virtual devices, the app works with Android 8.0 (API level 26).
        // It crashes because of LiteRT on earlier versions.
        // LiteRT documentation only states that version 1.2.0 requires Android 12:
        // https://ai.google.dev/edge/litert/android/index
        minSdk = 26
        targetSdk = 36
        versionCode = 3 // increment by 3 because of ABI-specific APKs
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API_BASE_URL comes from the git-ignored local.properties or the
        // API_BASE_URL environment variable, so no backend URL is hardcoded
        // in the repository. The build fails if it is missing.
        val apiBaseUrl = readApiBaseUrl()
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    // The app switches locale at runtime (in-app language setting, see
    // AppLocaleOverride) without Play Core, so all languages must ship in the
    // bundle instead of using language splits (AppBundleLocaleChanges).
    bundle {
        language {
            enableSplit = false
        }
    }

    val hasSigning = listOf(
        "RELEASE_STORE_FILE",
        "RELEASE_STORE_PASSWORD",
        "RELEASE_KEY_ALIAS",
        "RELEASE_KEY_PASSWORD"
    ).all { project.hasProperty(it) }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // See https://developer.android.com/build/configure-apk-splits
    val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    splits {
        abi {
            // Disable split ABIs when building appBundle: https://issuetracker.google.com/issues/402800800
            isEnable = !isBuildingBundle
            reset()
            //noinspection ChromeOsAbiSupport
            include(*abiCodes.keys.toTypedArray())
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            // Permite construir Uris/parcelables en tests unitarios locales
            isReturnDefaultValues = true
            // Robolectric necesita los recursos Android para simular el framework
            isIncludeAndroidResources = true
        }
    }
}

apply(from = file("download-tflite.gradle.kts"))

dependencies {

    implementation(project(":imageprocessing")) {
        exclude(group = "org.openpnp", module = "opencv")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.litert)
    implementation(libs.litert.support)
    implementation(libs.litert.metadata)
    implementation(libs.opencv)
    implementation(libs.pdfbox) {
        // To reduce APK size
        exclude("org.bouncycastle")
    }
    implementation(libs.icons.extended)
    implementation(libs.zoomable)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tesseract4android)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.translation)
    implementation(libs.mlkit.language.id)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Cloud module dependencies
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.security.crypto)
    // Biometric authentication for optional Cloud unlock (local to the device;
    // biometric data never leaves the device). Stable AndroidX API, min API 23.
    implementation(libs.androidx.biometric)

    // Push notifications (FCM). The Firebase BoM pins compatible artifact
    // versions; google-services.json (app config, NOT a secret) is added under
    // app/ during Firebase Console onboarding. No Firebase secret ever lives
    // in this app: the server-side service account is backend-only.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // Robolectric runs the Keystore / BiometricManager layers against a real
    // (simulated) Android framework on the JVM. Tests pin @Config(sdk=[28])
    // for stability; SDK 36 needs JDK 21 (Gradle already runs on JDK 21).
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

aboutLibraries {
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
        // Configure the duplication rule, to match "duplicates" with
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
    }
}

// See https://developer.android.com/build/configure-apk-splits
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory("build/generated/assets")

        variant.outputs.forEach { output ->
            val name =
                output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val baseAbiCode = abiCodes[name]
            if (baseAbiCode != null) {
                output.versionCode.set(output.versionCode.get() + baseAbiCode)
            }
        }
    }
}