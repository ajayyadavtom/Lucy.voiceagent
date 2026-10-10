// Clear conflicting ANDROID_PREFS_ROOT environment variable to prevent AGP AndroidLocationsException
try {
    val processEnvironment = Class.forName("java.lang.ProcessEnvironment")
    val envField = processEnvironment.getDeclaredField("theEnvironment")
    envField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val env = envField.get(null) as? MutableMap<String, String>
    env?.remove("ANDROID_PREFS_ROOT")

    val ciEnvField = processEnvironment.getDeclaredField("theCaseInsensitiveEnvironment")
    ciEnvField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val ciEnv = ciEnvField.get(null) as? MutableMap<String, String>
    ciEnv?.remove("ANDROID_PREFS_ROOT")
} catch (e: Throwable) {
    // Ignore reflection errors on non-standard JVMs
}

pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
		maven { url = uri("https://alphacephei.com/maven/") }
		maven { url = uri("https://jcenter.bintray.com") }
	}
}

rootProject.name = "Lucy.voiceagent"
include(":app")
