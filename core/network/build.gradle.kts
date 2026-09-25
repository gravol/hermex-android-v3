plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.hermex.core.network"
    compileSdk = 34

    defaultConfig {
        minSdk = 34
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
    // OkHttp (raw — no Retrofit) — api so downstream modules can use SSE
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:okhttp-sse:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Kotlinx Serialization — api so downstream modules can use JsonElement
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AndroidX Security Crypto (encrypted cookie storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
