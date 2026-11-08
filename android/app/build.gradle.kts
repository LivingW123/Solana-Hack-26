plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun prop(name: String, default: String) = providers.gradleProperty(name).getOrElse(default)

android {
    namespace = "app.taptrade"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.taptrade"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "BACKEND_URL", "\"${prop("taptrade.backendUrl", "http://10.0.2.2:3000")}\"")
        buildConfigField("boolean", "FAKE_BACKEND", prop("taptrade.fakeBackend", "true"))
        // The app reads chain state (confirmations, escrow websocket) straight from RPC, not via the backend.
        buildConfigField("String", "RPC_URL", "\"${prop("taptrade.rpcUrl", "https://api.devnet.solana.com")}\"")
        buildConfigField("String", "PROGRAM_ID","\"${prop("taptrade.programId", "")}\"")
        buildConfigField("String", "TUSDC_MINT", "\"${prop("taptrade.tusdcMint", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Solana JSON-RPC over HTTP + websocket (accountSubscribe for the seller's "Paid ✓")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Mobile Wallet Adapter (Solana Mobile)
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.3")
}
