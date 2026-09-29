plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vyan.iptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vyan.iptv"
        minSdk = 24
        // Target 36 to match
        targetSdk = 36
        versionCode = 8 // MUST strictly increase with every single build (beta or stable)
        versionName = "v0.00.08" // The base version you are working towards

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // 1. Stable Production Build
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }

        // 2. Pre-release / Beta Build
        create("prerelease") {
            initWith(getByName("release")) // Inherits all settings from "release"

            // Appends "-beta" to your versionName (e.g., "v0.00.08-beta")
            versionNameSuffix = "-beta"

            // Appends ".beta" to your package name so you can install both the stable
            // and beta versions on your device simultaneously.
            applicationIdSuffix = ".beta"

            // Optional: Enables debugging in the pre-release build even though it's built like a release
            isDebuggable = true

            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Retrofit (for communicating with the Xtream API)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Media3 ExoPlayer (for streaming live TV and VOD)
    val media3_version = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-ui:$media3_version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3_version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3_version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3_version")

    // Coil (for loading and displaying movie posters and channel logos)
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.json:json:20231013")

    //android background worker
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
}