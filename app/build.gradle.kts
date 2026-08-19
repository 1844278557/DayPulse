import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.daypulse"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.daypulse"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

configurations.configureEach {
    resolutionStrategy {
        force("androidx.core:core:1.17.0")
        force("androidx.core:core-ktx:1.17.0")
        force("androidx.lifecycle:lifecycle-common:2.10.0")
        force("androidx.lifecycle:lifecycle-common-jvm:2.10.0")
        force("androidx.lifecycle:lifecycle-runtime:2.10.0")
        force("androidx.lifecycle:lifecycle-runtime-android:2.10.0")
        force("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
        force("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
        force("androidx.lifecycle:lifecycle-runtime-compose-android:2.10.0")
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
