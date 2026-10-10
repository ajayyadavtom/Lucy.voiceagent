package com.lucy.voiceagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import timber.log.Timber

/**
 * Persistent Foreground Service using Vosk SpeechService and StorageService.unpack()
 * to continuously listen for the wake word 'lucy' and execute commands.
 */
class VoiceCommandService : Service(), RecognitionListener {

	private val binder = LocalBinder()
	private var speechService: SpeechService? = null
	private var recognizer: Recognizer? = null
	private var model: Model? = null

	private val mainHandler = Handler(Looper.getMainLooper())
	private val executor = VoiceCommandExecutor()

	companion object {
		private const val CHANNEL_ID = "VoiceCommandServiceChannel"
		private const val NOTIFICATION_ID = 2001
	}

	inner class LocalBinder : Binder() {
		fun getService(): VoiceCommandService = this@VoiceCommandService
	}

	override fun onCreate() {
		super.onCreate()
		Timber.d("VoiceCommandService created")
		LibVosk.setLogLevel(LogLevel.INFO)
		createNotificationChannel()
		startForeground(NOTIFICATION_ID, getNotification("Initializing Vosk Model..."))
		initVoskModel()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == "STOP_SERVICE") {
			stopSelf()
			return START_NOT_STICKY
		}
		return START_STICKY
	}

	override fun onBind(intent: Intent?): IBinder = binder

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				CHANNEL_ID,
				"Lucy Voice Assistant Service",
				NotificationManager.IMPORTANCE_LOW
			)
			val manager = getSystemService(NotificationManager::class.java)
			manager?.createNotificationChannel(channel)
		}
	}

	private fun getNotification(text: String): Notification {
		val stopIntent = Intent(this, VoiceCommandService::class.java).setAction("STOP_SERVICE")
		val stopPendingIntent = PendingIntent.getService(
			this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Lucy Voice Agent")
			.setContentText(text)
			.setSmallIcon(android.R.drawable.ic_dialog_info)
			.setOngoing(true)
			.addAction(0, "Stop", stopPendingIntent)
			.build()
	}

	private fun initVoskModel() {
		StorageService.unpack(
			this,
			"model-en-us.zip",
			"model",
			{ unpackedModel ->
				model = unpackedModel
				try {
					recognizer = Recognizer(model, 16000f)
					speechService = SpeechService(recognizer, 16000f)
					speechService?.startListening(this)
					Timber.i("Vosk SpeechService started listening successfully")
					Log.d("LucyVoice", "Vosk SpeechService started listening successfully")
					updateNotification("Listening for 'Lucy'...")
				} catch (e: Exception) {
					Timber.e(e, "Failed to create Vosk Recognizer or SpeechService")
					Log.e("LucyVoice", "Failed to create Vosk Recognizer", e)
				}
			},
			{ exception ->
				Timber.e(exception, "Failed to unpack Vosk model")
				Log.e("LucyVoice", "Failed to unpack Vosk model", exception)
				updateNotification("Model Unpack Failed")
			}
		)
	}

	private fun updateNotification(text: String) {
		val manager = getSystemService(NotificationManager::class.java)
		manager?.notify(NOTIFICATION_ID, getNotification(text))
	}

	override fun onPartialResult(hypothesis: String?) {
		// Optional partial results
	}

	override fun onResult(hypothesis: String?) {
		handleRecognitionResult(hypothesis)
	}

	override fun onFinalResult(hypothesis: String?) {
		handleRecognitionResult(hypothesis)
	}

	private fun handleRecognitionResult(hypothesis: String?) {
		if (hypothesis.isNullOrEmpty()) return
		try {
			val json = JSONObject(hypothesis)
			val text = json.optString("text", "").lowercase().trim()

			if (text.isEmpty()) return

			var command = ""
			if (text.contains("lucy")) {
				val index = text.indexOf("lucy")
				command = text.substring(index + "lucy".length).trim()
					.removePrefix(",")
					.removePrefix("-")
					.trim()
			}

			Log.d("LucyVoice", "Heard text: $text | Command: $command")
			Timber.d("Heard text: $text | Command: $command")

			if (command.isNotEmpty()) {
				mainHandler.post {
					updateNotification("Executing: $command")
					executor.execute(applicationContext, command)
				}
				mainHandler.postDelayed({
					updateNotification("Listening for 'Lucy'...")
				}, 3000)
			}
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error processing recognition result", e)
		}
	}

	override fun onError(exception: Exception?) {
		Timber.e(exception, "Vosk Recognition error")
		Log.e("LucyVoice", "Vosk Recognition error", exception)
	}

	override fun onTimeout() {
		Timber.i("Vosk Recognition timeout")
		speechService?.startListening(this)
	}

	override fun onDestroy() {
		super.onDestroy()
		speechService?.stop()
		speechService?.shutdown()
		recognizer?.close()
		model?.close()
		Timber.d("VoiceCommandService destroyed")
	}
}
