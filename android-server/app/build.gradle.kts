import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// keystore.properties (tidak di-push): storeFile, storePassword, keyAlias,
// keyPassword, dan opsional privateKey (kunci mode pribadi, di-inject ke BuildConfig).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.miku.mikuremote.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miku.mikuremote.server"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.4.0"
        // URL VPS production (dengan path /mikuremote); bisa dioverride lewat settings di aplikasi.
        buildConfigField("String", "DEFAULT_VPS_URL", "\"https://ashimusic.biz.id/mikuremote\"")
        // Kunci mode pribadi; kosong = diisi manual lewat UI.
        buildConfigField("String", "PRIVATE_KEY", "\"${keystoreProps.getProperty("privateKey") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            // Hanya konfigurasi signing jika keystore benar-benar ada di disk.
            // Dulu: storeFile selalu di-set (path keystore.properties), sehingga
            // assembleRelease gagal keras saat file tidak ada — mis. build di
            // CI/GitHub Actions yang tidak punya keystore.
            val storePath = keystoreProps.getProperty("storeFile")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingConfigs["release"].storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
