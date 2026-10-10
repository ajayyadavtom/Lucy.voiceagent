pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
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
