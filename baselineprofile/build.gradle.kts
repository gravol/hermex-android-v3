plugins {
    // AGP and Kotlin are already on the build classpath; re-requesting them with a
    // version here fails compatibility resolution. Only the baselineprofile plugin is new.
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.hermex.baselineprofile"
    compileSdk = 35

    // A com.android.test module instruments another project — ours is the app.
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 34
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    // We drive the connected test bench (Pixel 8) directly; no managed emulator needed.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.profileinstaller)
}
