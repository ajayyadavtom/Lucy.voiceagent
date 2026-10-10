package com.lucy.voiceagent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import timber.log.Timber

/**
 * Safe intent mapper: routes recognized speech to actual Android Intents to launch apps.
 * Bypasses speaker verification for basic app launches (YouTube, Spotify, Chrome, Gmail, Maps, etc.).
 */
class VoiceCommandExecutor {

	/**
	 * Map spoken command string to actual Android Intent and launch the app.
	 * Includes Intent.FLAG_ACTIVITY_NEW_TASK flag so it can be launched from a Service.
	 */
	fun executeCommand(context: Context, command: String): Boolean {
		val text = command.lowercase().trim()

		Log.d("LucyVoice", "VoiceCommandExecutor received command: '$text'")
		Timber.d("Executing command: '$text'")

		return when {
			// YouTube
			text.contains("open youtube") -> launchApp(context, "com.google.android.youtube")

			// Spotify
			text.contains("open spotify") -> launchApp(context, "com.spotify.music")

			// Chrome
			text.contains("open chrome") -> launchApp(context, "com.android.chrome")

			// Gmail
			text.contains("open gmail") -> launchApp(context, "com.google.android.gm")

			// Maps
			text.contains("open maps") -> launchApp(context, "com.google.android.apps.maps")

			// Calculator
			text.contains("open calculator") -> launchApp(context, "com.android.calculator2")

			// Settings
			text.contains("open settings") -> launchApp(context, "com.android.settings")

			// General launcher for any installed app by name ("open instagram", "open camera", etc.)
			text.startsWith("open ") -> {
				val appQuery = text.removePrefix("open ").trim()
				launchAppByQuery(context, appQuery)
			}

			// Media Control Intents
			text.contains("play") -> sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY)
			text.contains("pause") -> sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
			text.contains("next") -> sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT)
			text.contains("previous") || text.contains("prev") -> sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

			else -> {
				Log.d("LucyVoice", "Command not mapped: '$text'")
				Timber.w("Unrecognized command: '$text'")
				false
			}
		}
	}

	/**
	 * Search installed apps dynamically by query (e.g., "youtube", "instagram", "camera") and launch.
	 */
	private fun launchAppByQuery(context: Context, query: String): Boolean {
		if (query.isBlank()) return false
		try {
			val pm = context.packageManager
			val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
				addCategory(Intent.CATEGORY_LAUNCHER)
			}
			val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
			for (info in resolveInfos) {
				val appLabel = info.loadLabel(pm).toString().lowercase()
				if (appLabel.contains(query) || query.contains(appLabel)) {
					val packageName = info.activityInfo.packageName
					Log.d("LucyVoice", "Found app matching query '$query': $packageName ($appLabel)")
					Timber.i("Found app matching query '$query': $packageName ($appLabel)")
					return launchApp(context, packageName)
				}
			}
			Log.w("LucyVoice", "No installed app found matching query: '$query'")
			Timber.w("No installed app found matching query: '$query'")
			return false
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error finding app by query: '$query'", e)
			Timber.e(e, "Error finding app by query: '$query'")
			return false
		}
	}

	/**
	 * Launch app by package name using getLaunchIntentForPackage with FLAG_ACTIVITY_NEW_TASK.
	 */
	private fun launchApp(context: Context, packageName: String): Boolean {
		return try {
			val intent = context.packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
				Log.d("LucyVoice", "Successfully launched app: $packageName")
				Timber.i("Launched app: $packageName")
				true
			} else {
				Log.w("LucyVoice", "App not installed on device: $packageName")
				Timber.w("App not found: $packageName")
				false
			}
		} catch (e: Exception) {
			Log.e("LucyVoice", "Failed to launch app: $packageName", e)
			Timber.e(e, "Failed to launch app: $packageName")
			false
		}
	}

	/**
	 * Dispatch a media key event.
	 */
	private fun sendMediaKeyEvent(context: Context, keyCode: Int): Boolean {
		return try {
			val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
			audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
			audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
			Log.d("LucyVoice", "Dispatched media key event: $keyCode")
			Timber.i("Media key event sent: $keyCode")
			true
		} catch (e: Exception) {
			Log.e("LucyVoice", "Failed to send media key event: $keyCode", e)
			Timber.e(e, "Failed to send media key event: $keyCode")
			false
		}
	}

	/**
	 * Get list of whitelisted app names for help.
	 */
	fun getWhitelistedApps(): List<String> {
		return listOf(
			"YouTube",
			"Spotify",
			"Chrome",
			"Gmail",
			"Maps",
			"Calculator",
			"Settings"
		)
	}

	/**
	 * Get list of whitelisted media commands.
	 */
	fun getMediaCommands(): List<String> {
		return listOf("play", "pause", "next", "previous")
	}
}
