plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    compileSdkVersion(36)
    namespace = "com.lucy.voiceagent"

    defaultConfig {
        applicationId = "com.lucy.voiceagent"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
