package com.lucy.voiceagent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import timber.log.Timber

/**
 * Safe intent mapper: routes recognized speech to a whitelist of allowed actions.
 * No arbitrary command execution. All actions are pre-approved.
 */
class VoiceCommandExecutor(private val context: Context) {

	private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

	/**
	 * Map voice text to an intent-based action.
	 * Returns true if command was recognized and executed.
	 */
	fun executeCommand(voiceText: String, onRequiresConfirmation: ((String) -> Unit)? = null): Boolean {
		val normalizedText = voiceText.lowercase().trim()

		Timber.d("Executing command: $normalizedText")

		return when {
			// App launching commands
			normalizedText.contains("open spotify") -> launchApp("com.spotify.music")
			normalizedText.contains("open chrome") -> launchApp("com.android.chrome")
			normalizedText.contains("open gmail") -> launchApp("com.google.android.gm")
			normalizedText.contains("open maps") -> launchApp("com.google.android.apps.maps")
			normalizedText.contains("open calculator") -> launchApp("com.android.calculator2")
			normalizedText.contains("open settings") -> launchApp("com.android.settings")

			// Media control commands
			normalizedText.contains("play") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
			normalizedText.contains("pause") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
			normalizedText.contains("next") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
			normalizedText.contains("previous") || normalizedText.contains("prev") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

			// Sensitive commands (require confirmation/verification)
			normalizedText.contains("shutdown") -> {
				onRequiresConfirmation?.invoke("confirm shutdown")
				true // Handled by callback; executor does not call device admin directly
			}

			normalizedText.contains("reboot") || normalizedText.contains("restart") -> {
				onRequiresConfirmation?.invoke("confirm reboot")
				true
			}

			normalizedText.contains("lock") || normalizedText.contains("lock screen") -> {
				onRequiresConfirmation?.invoke("confirm lock")
				true
			}

			// Unknown command
			else -> {
				Timber.w("Unknown command: $normalizedText")
				false
			}
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
