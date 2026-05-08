import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load local.properties for secret defaults (file is gitignored)
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) load(localPropsFile.inputStream())
}

fun localProp(key: String, fallback: String): String =
    localProperties.getProperty(key, fallback)

android {
    namespace = "com.example.nfcapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.nfcapp"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject WiFi / WebSocket defaults from local.properties into BuildConfig
        buildConfigField("String", "DEFAULT_WIFI_SSID", "\"${localProp("nfcapp.wifi.ssid", "")}\"")
        buildConfigField("String", "DEFAULT_WIFI_PASSWORD", "\"${localProp("nfcapp.wifi.password", "")}\"")
        buildConfigField("String", "DEFAULT_WS_IP", "\"${localProp("nfcapp.ws.ip", "10.0.0.1")}\"")
        buildConfigField("String", "DEFAULT_WS_PORT", "\"${localProp("nfcapp.ws.port", "8080")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
