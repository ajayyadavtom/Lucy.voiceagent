plugins {
	id("com.android.application")
	kotlin("android")
	id("kotlin-kapt")
}

android {
	namespace = "com.lucy.voiceagent"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.lucy.voiceagent"
		minSdk = 28
		targetSdk = 35
		versionCode = 1
		versionName = "1.0.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

	kotlinOptions {
		jvmTarget = "11"
	}

	buildFeatures {
		aidl = true
		buildConfig = true
	}
}

dependencies {
	// AndroidX & Core
	implementation("androidx.core:core-ktx:1.12.0")
	implementation("androidx.appcompat:appcompat:1.6.1")
	implementation("com.google.android.material:material:1.10.0")
	implementation("androidx.constraintlayout:constraintlayout:2.1.4")
	implementation("androidx.cardview:cardview:1.0.0")

	// Kotlin & Coroutines
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

	// Google Generative AI (Gemini 1.5 Flash)
	implementation("com.google.ai.client.generativeai:generativeai:0.7.0")

	// Gson
	implementation("com.google.code.gson:gson:2.10.1")

	// OkHttp
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

	// Retrofit
	implementation("com.squareup.retrofit2:retrofit:2.9.0")
	implementation("com.squareup.retrofit2:converter-gson:2.9.0")

	// Androidx Work & Lifecycle
	implementation("androidx.work:work-runtime-ktx:2.8.1")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
	implementation("androidx.lifecycle:lifecycle-process:2.6.2")

	// Logging
	implementation("com.jakewharton.timber:timber:5.0.1")

	// Testing
	testImplementation("junit:junit:4.13.2")
	androidTestImplementation("androidx.test.ext:junit:1.1.5")
	androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
