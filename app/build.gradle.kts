plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hermex.v3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermex.v3"
        minSdk = 34
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ── Signing config: consistent key across all machines ──
    // Local builds: keystore.properties (gitignored) at project root
    // CI builds: KEYSTORE_BASE64 + KEYSTORE_PASSWORD + KEY_ALIAS + KEY_PASSWORD env vars
    // MUST be declared before buildTypes which references signingConfigs.release
    val localProps = rootProject.file("keystore.properties")
    if (localProps.exists()) {
        val lines = localProps.readLines()
        fun lineValue(prefix: String): String? =
            lines.firstOrNull { it.startsWith(prefix) }?.substringAfter("=")?.trim()
        val sf = lineValue("storeFile") ?: error("keystore.properties: storeFile required")
        val sp = lineValue("storePassword") ?: error("keystore.properties: storePassword required")
        val ka = lineValue("keyAlias") ?: error("keystore.properties: keyAlias required")
        val kp = lineValue("keyPassword") ?: error("keystore.properties: keyPassword required")
        signingConfigs.create("release") {
            storeFile = rootProject.file(sf)
            storePassword = sp
            keyAlias = ka
            keyPassword = kp
        }
    } else {
        val ciBase64 = providers.environmentVariable("KEYSTORE_BASE64").orNull
        val storePass = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
        if (ciBase64 != null && storePass != null) {
            val decodedFile = layout.buildDirectory.file("ci-release.keystore").get().asFile
            decodedFile.parentFile.mkdirs()
            decodedFile.writeBytes(
                ciBase64.encodeToByteArray().let { raw ->
                    ProcessBuilder("base64", "-d")
                        .redirectInput(ProcessBuilder.Redirect.PIPE)
                        .redirectOutput(decodedFile)
                        .start().also { proc ->
                            proc.outputStream.write(raw)
                            proc.outputStream.close()
                            proc.waitFor()
                        }
                    decodedFile.readBytes()
                }
            )
            val ciKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull ?: "hermex"
            val ciKeyPass = providers.environmentVariable("KEY_PASSWORD").orNull ?: ""
            signingConfigs.create("release") {
                storeFile = decodedFile
                storePassword = storePass
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPass
            }
        } else {
            signingConfigs.create("release") { }
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            // Keep same app ID as release for Obtanium updates
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // OkHttp (raw, no Retrofit — matches iOS URLSession)
    implementation(libs.okhttp.logging.interceptor)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp("androidx.room:room-compiler:2.6.1")

    // Coil
    implementation(libs.coil.compose)
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.34.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.34.0")

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")

    // Project modules
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}