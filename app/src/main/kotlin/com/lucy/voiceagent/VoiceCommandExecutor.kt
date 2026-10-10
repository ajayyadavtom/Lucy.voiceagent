package com.lucy.voiceagent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import timber.log.Timber

/**
 * Safe intent mapper: routes recognized speech to allowed actions or app launches.
 * Supports wake word prefixes like "Lucy", "Hey Lucy", "OK Lucy".
 */
class VoiceCommandExecutor(private val context: Context) {

	private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

	/**
	 * Map voice text to an intent-based action.
	 * Returns true if command was recognized and executed.
	 */
	fun executeCommand(voiceText: String, onRequiresConfirmation: ((String) -> Unit)? = null): Boolean {
		var text = voiceText.lowercase().trim()

		// Strip common wake words / prefixes ("hey lucy", "ok lucy", "hello lucy", "lucy")
		val wakeWords = listOf("hey lucy", "ok lucy", "hello lucy", "lucy")
		for (wakeWord in wakeWords) {
			if (text.startsWith(wakeWord)) {
				text = text.substring(wakeWord.length).trim()
				break
			}
		}

		Timber.d("Executing processed command: '$text' (original: '$voiceText')")

		return when {
			// App launching commands
			text.contains("open spotify") -> launchApp("com.spotify.music")
			text.contains("open chrome") -> launchApp("com.android.chrome")
			text.contains("open gmail") -> launchApp("com.google.android.gm")
			text.contains("open maps") -> launchApp("com.google.android.apps.maps")
			text.contains("open calculator") -> launchApp("com.android.calculator2")
			text.contains("open settings") -> launchApp("com.android.settings")
			text.startsWith("open ") -> {
				val appQuery = text.removePrefix("open ").trim()
				launchAppByQuery(appQuery)
			}

			// Media control commands
			text.contains("play") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
			text.contains("pause") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
			text.contains("next") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
			text.contains("previous") || text.contains("prev") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

			// Sensitive commands (require confirmation/verification)
			text.contains("shutdown") -> {
				onRequiresConfirmation?.invoke("confirm shutdown")
				true
			}

			text.contains("reboot") || text.contains("restart") -> {
				onRequiresConfirmation?.invoke("confirm reboot")
				true
			}

			text.contains("lock") || text.contains("lock screen") -> {
				onRequiresConfirmation?.invoke("confirm lock")
				true
			}

			// Unknown command
			else -> {
				Timber.w("Unknown command: $text")
				false
			}
		}
	}

	/**
	 * Search installed apps dynamically by query (e.g., "youtube", "instagram", "camera") and launch.
	 */
	private fun launchAppByQuery(query: String): Boolean {
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
					Timber.i("Found app matching query '$query': $packageName ($appLabel)")
					return launchApp(packageName)
				}
			}
			Timber.w("No installed app found matching query: '$query'")
			return false
		} catch (e: Exception) {
			Timber.e(e, "Error finding app by query: '$query'")
			return false
		}
	}

	/**
	 * Launch app by package name. Returns true if app found.
	 */
	private fun launchApp(packageName: String): Boolean {
		return try {
			val intent = context.packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
				context.startActivity(intent)
				Timber.i("Launched app: $packageName")
				true
			} else {
				Timber.w("App not found: $packageName")
				false
			}
		} catch (e: Exception) {
			Timber.e(e, "Failed to launch app: $packageName")
			false
		}
	}

	/**
	 * Dispatch a media key event.
	 */
	private fun sendMediaKeyEvent(keyCode: Int): Boolean {
		return try {
			audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
			audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
			Timber.i("Media key event sent: $keyCode")
			true
		} catch (e: Exception) {
			Timber.e(e, "Failed to send media key event: $keyCode")
			false
		}
	}

	/**
	 * Get list of whitelisted app names for help.
	 */
	fun getWhitelistedApps(): List<String> {
		return listOf(
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
