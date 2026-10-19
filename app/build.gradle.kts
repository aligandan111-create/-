import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload-key signing: read from keystore.properties (local) or environment variables (GitHub Actions).
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun keyValue(name: String, env: String): String? = keyProps.getProperty(name) ?: System.getenv(env)

android {
    namespace = "com.bequeen.lujain"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bequeen.lujain"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
    }

    signingConfigs {
        create("upload") {
            val store = keyValue("storeFile", "LUJAIN_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = keyValue("storePassword", "LUJAIN_KEYSTORE_PASSWORD")
                keyAlias = keyValue("keyAlias", "LUJAIN_KEY_ALIAS")
                keyPassword = keyValue("keyPassword", "LUJAIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keyValue("storeFile", "LUJAIN_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // native libraries stay uncompressed and 16 KB-aligned (required by Google Play)
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
