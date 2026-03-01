plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openclaw.phoneuse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openclaw.phoneuse"
        minSdk = 26  // API 26 = Android 8.0 (Ed25519 via net.i2p.crypto:eddsa library)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    
    // WebSocket (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON
    implementation("org.json:json:20240303")
    
    // Ed25519 via eddsa (pure Java implementation, works on all Android versions)
    implementation("net.i2p.crypto:eddsa:0.3.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
