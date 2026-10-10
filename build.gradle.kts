// Top-level build file
plugins {
	id("com.android.application") version "9.4.1" apply false
	kotlin("android") version "2.2.10" apply false
}

ext {
	set("android.useAndroidX", true)
	set("android.enableJetifier", true)
}

tasks.register("clean", Delete::class) {
	delete(rootProject.buildDir)
}
