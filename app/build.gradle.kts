import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Load local.properties for secret defaults (file is gitignored)
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) load(localPropsFile.inputStream())
}

fun localProp(key: String, fallback: String): String =
    localProperties.getProperty(key, fallback)

// Allow versionName / versionCode to be overridden from the command line, e.g. in CI:
//   ./gradlew assembleRelease -PversionName=1.2.3 -PversionCode=10203
val ciVersionName: String = project.findProperty("versionName") as String? ?: "1.0"
val ciVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.luckytap.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("SIGNING_STORE_FILE") ?: ""
            val storePass    = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
            val alias        = System.getenv("SIGNING_KEY_ALIAS") ?: ""
            val keyPass      = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
            if (storeFilePath.isNotEmpty()) {
                storeFile     = file(storeFilePath)
                storePassword = storePass
                keyAlias      = alias
                keyPassword   = keyPass
            }
        }
    }

    defaultConfig {
        applicationId = "com.luckytap.app"
        minSdk = 23
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject WiFi / WebSocket defaults from local.properties into BuildConfig
        buildConfigField("String", "DEFAULT_WIFI_SSID", "\"${localProp("luckytap.wifi.ssid", "")}\"")
        buildConfigField("String", "DEFAULT_WIFI_PASSWORD", "\"${localProp("luckytap.wifi.password", "")}\"")
        buildConfigField("String", "DEFAULT_WS_IP", "\"${localProp("luckytap.ws.ip", "10.0.0.1")}\"")
        buildConfigField("String", "DEFAULT_WS_PORT", "\"${localProp("luckytap.ws.port", "8080")}\"")
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
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Allows use of java.time and other Java 8+ APIs on API < 26 via D8/R8 desugaring.
        // Required if minSdk is ever lowered below 26, and is a zero-cost defensive measure otherwise.
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    jvmToolchain(11)
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

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
