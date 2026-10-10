package com.lucy.voiceagent

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import timber.log.Timber
import java.util.Locale

/**
 * VoiceCommandExecutor: Maps voice commands to Android Intents with TextToSpeech audio feedback
 * and FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP for launching apps from background services.
 */
class VoiceCommandExecutor {

	private var tts: TextToSpeech? = null

	fun execute(context: Context, command: String) {
		val text = command.lowercase().trim()
		Log.d("LucyVoice", "VoiceCommandExecutor executing command: '$text'")
		Timber.d("VoiceCommandExecutor executing command: '$text'")

		if (tts == null) {
			tts = TextToSpeech(context.applicationContext) { status ->
				if (status == TextToSpeech.SUCCESS) {
					tts?.language = Locale.US
					speakAndLaunch(context, text)
				} else {
					launchApp(context, text)
				}
			}
		} else {
			speakAndLaunch(context, text)
		}
	}

	private fun speakAndLaunch(context: Context, command: String) {
		val appName = when {
			command.contains("youtube") -> "YouTube"
			command.contains("spotify") -> "Spotify"
			command.contains("chrome") -> "Chrome"
			command.contains("gmail") -> "Gmail"
			command.contains("maps") -> "Google Maps"
			command.contains("calculator") -> "Calculator"
			command.contains("settings") -> "Settings"
			else -> command.removePrefix("open").trim().replaceFirstChar { it.uppercase() }
		}

		val announcement = "Opening $appName"
		tts?.speak(announcement, TextToSpeech.QUEUE_FLUSH, null, null)

		// Delay briefly to allow audio feedback to start before launching the intent
		android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
			launchApp(context, command)
		}, 500)
	}

	private fun launchApp(context: Context, command: String) {
		val packageName = when {
			command.contains("youtube") -> "com.google.android.youtube"
			command.contains("spotify") -> "com.spotify.music"
			command.contains("chrome") -> "com.android.chrome"
			command.contains("gmail") -> "com.google.android.gm"
			command.contains("maps") -> "com.google.android.apps.maps"
			command.contains("calculator") -> "com.android.calculator2"
			command.contains("settings") -> "com.android.settings"
			else -> null
		}

		if (packageName != null) {
			try {
				val intent = context.packageManager.getLaunchIntentForPackage(packageName)
				if (intent != null) {
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
					context.startActivity(intent)
					Log.d("LucyVoice", "Successfully launched package: $packageName")
					Timber.i("Successfully launched package: $packageName")
					return
				} else {
					Log.w("LucyVoice", "Launch intent returned null for package: $packageName")
				}
			} catch (e: Exception) {
				Log.e("LucyVoice", "Failed to launch package: $packageName", e)
			}
		}

		// Generic fallback: Query installed applications
		if (command.contains("open") || command.isNotBlank()) {
			val query = command.removePrefix("open").trim()
			launchAppByQuery(context, query)
		}
	}

	private fun launchAppByQuery(context: Context, query: String) {
		if (query.isBlank()) return
		try {
			val pm = context.packageManager
			val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
				addCategory(Intent.CATEGORY_LAUNCHER)
			}
			val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
			for (info in resolveInfos) {
				val label = info.loadLabel(pm).toString().lowercase()
				if (label.contains(query) || query.contains(label)) {
					val pkg = info.activityInfo.packageName
					val intent = pm.getLaunchIntentForPackage(pkg)
					if (intent != null) {
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
						context.startActivity(intent)
						Log.d("LucyVoice", "Successfully launched app by query '$query': $pkg")
						Timber.i("Successfully launched app by query '$query': $pkg")
						return
					}
				}
			}
			Log.w("LucyVoice", "No installed app matched query: '$query'")
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error querying installed apps for '$query'", e)
		}
	}
}
