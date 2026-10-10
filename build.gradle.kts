// Top-level build file
plugins {
	id("com.android.application") version "8.13.2" apply false
	kotlin("android") version "1.8.10" apply false
}

ext {
	set("android.useAndroidX", true)
	set("android.enableJetifier", true)
}

tasks.register("clean", Delete::class) {
	delete(rootProject.buildDir)
}
