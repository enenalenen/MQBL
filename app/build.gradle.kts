// app/build.gradle.kts (for MQBL Project)

plugins {
    // Use aliases from the merged libs.versions.toml
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Use the unified kotlin-compose alias
    alias(libs.plugins.kotlin.compose)
}

android {
    // New namespace for the merged project
    namespace = "com.example.mqbl"
    compileSdk = 35 // Keep from original projects

    defaultConfig {
        // New application ID
        applicationId = "com.example.mqbl"
        // Use the higher minSdk from BLEv2 to ensure compatibility for both features
        minSdk = 29
        targetSdk = 35 // Keep from original projects
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true // Include from MQTTTest
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Keep as false for now, enable and configure ProGuard if needed
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro" // Remember to configure this file if minify is enabled
            )
        }
    }
    compileOptions {
        // Use Java 11 (from BLEv2, more modern)
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        // Match Java 11
        jvmTarget = "11"
    }
    buildFeatures {
        // Enable Jetpack Compose
        compose = true
    }
    composeOptions {
        // Set Compose Compiler version using the alias from merged TOML
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging { // Use the modern 'packaging' block name
        resources {
            // Include excludes from MQTTTest to prevent license conflicts
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // Core & AppCompat (AppCompat/ActivityKTX added for consistency from BLEv2)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat) // Added via merged TOML
    implementation(libs.androidx.activity.ktx) // Added via merged TOML
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose BOM and implementation dependencies
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Navigation Compose (Essential for page switching between BLE and MQTT screens)
    implementation(libs.androidx.navigation.compose)

    // Paho MQTT Library (Essential for MQTT functionality)
    implementation(libs.paho.mqtt.android)


    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom)) // Compose BOM for testing
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Debug dependencies
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

}