package com.lucy.voiceagent

import android.annotation.SuppressLint
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import timber.log.Timber
import java.io.File

/**
 * ForegroundService for continuous voice listening.
 * Uses Vosk SpeechService for offline STT and routes commands to VoiceCommandExecutor.
 */
class VoiceCommandService : Service(), RecognitionListener {

	private val binder = LocalBinder()
	private var speechService: SpeechService? = null
	private var recognizer: Recognizer? = null
	private var model: Model? = null

	private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
	private val mainHandler = Handler(Looper.getMainLooper())

	private lateinit var executor: VoiceCommandExecutor
	private lateinit var speakerVerifier: SpeakerVerifier

	private val notificationManager: NotificationManager by lazy {
		getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
	}

	companion object {
		private const val CHANNEL_ID = "VoiceCommandService"
		private const val NOTIFICATION_ID = 1001
		private const val MODEL_DIR = "vosk_model"
		private const val MODEL_FILE = "model-en-us"
	}

	inner class LocalBinder : Binder() {
		fun getService(): VoiceCommandService = this@VoiceCommandService
	}

	override fun onCreate() {
		super.onCreate()
		Timber.d("VoiceCommandService created")

		LibVosk.setLogLevel(LogLevel.INFO)
		executor = VoiceCommandExecutor(this)
		speakerVerifier = SpeakerVerifier(this)

		createNotificationChannel()
		initializeVosk()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		Timber.d("VoiceCommandService started")
		if (intent?.action == "STOP_SERVICE") {
			stopSelf()
			return START_NOT_STICKY
		}
		startListening()
		return START_STICKY
	}

	override fun onBind(intent: Intent?): IBinder = binder

	/**
	 * Create notification channel for foreground service.
	 */
	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				CHANNEL_ID,
				"Voice Command Listening",
				NotificationManager.IMPORTANCE_LOW
			)
			channel.description = "Listening for voice commands"
			notificationManager.createNotificationChannel(channel)
		}
	}

	/**
	 * Create foreground notification.
	 */
	private fun getNotification(status: String = "Listening"): Notification {
		val stopIntent = Intent(this, VoiceCommandService::class.java).apply {
			action = "STOP_SERVICE"
		}
		val stopPendingIntent = PendingIntent.getService(
			this,
			0,
			stopIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Lucy Voice Agent")
			.setContentText(status)
			.setSmallIcon(android.R.drawable.ic_dialog_info)
			.setOngoing(true)
			.addAction(0, "Stop", stopPendingIntent)
			.build()
	}

	/**
	 * Initialize Vosk model, recognizer, and speech service.
	 */
	private fun initializeVosk() {
		serviceScope.launch {
			try {
				val modelDir = File(cacheDir, MODEL_DIR)
				val modelPath = File(modelDir, MODEL_FILE)

				if (!modelPath.exists()) {
					Timber.i("Model not found locally. Extracting from assets...")
					extractModelFromAssets(modelPath)
				}

				model = Model(modelPath.absolutePath)
				recognizer = Recognizer(model, 16000f)

				Timber.i("Vosk model loaded successfully")
				startForeground(NOTIFICATION_ID, getNotification("Listening for 'Lucy'..."))

				mainHandler.post {
					startListening()
				}

			} catch (e: Exception) {
				Timber.e(e, "Failed to initialize Vosk model")
				Log.e("LucyVoice", "Failed to initialize Vosk model", e)
				mainHandler.post {
					stopSelf()
				}
			}
		}
	}

	/**
	 * Extract model files from assets to cache directory.
	 */
	@SuppressLint("SuspiciousIndentation")
	private fun extractModelFromAssets(modelPath: File) {
		try {
			val assetManager = assets
			val modelZip = modelPath.parentFile?.let { File(it, "$MODEL_FILE.zip") }
				?: throw Exception("Cannot determine model path")

			val assetInputStream = try {
				assetManager.open("vosk/$MODEL_FILE.zip")
			} catch (e: Exception) {
				Timber.w("Model zip not found in assets. Download and place in assets/vosk/$MODEL_FILE.zip")
				throw Exception("Model file missing from assets/vosk/")
			}

			modelZip.parentFile?.mkdirs()
			assetInputStream.use { input ->
				modelZip.outputStream().use { output ->
					input.copyTo(output)
				}
			}

			unzipModel(modelZip, modelPath.parentFile!!)
			modelZip.delete()

			Timber.i("Model extracted successfully")
		} catch (e: Exception) {
			Timber.e(e, "Failed to extract model")
			throw e
		}
	}

	/**
	 * Minimal unzip utility for model extraction.
	 */
	private fun unzipModel(zipFile: File, outputDir: File) {
		val zip = java.util.zip.ZipFile(zipFile)
		zip.use { archive ->
			archive.entries().asSequence().forEach { entry ->
				val outputFile = File(outputDir, entry.name)
				if (entry.isDirectory) {
					outputFile.mkdirs()
				} else {
					outputFile.parentFile?.mkdirs()
					archive.getInputStream(entry).use { input ->
						outputFile.outputStream().use { output ->
							input.copyTo(output)
						}
					}
				}
			}
		}
	}

	/**
	 * Start speech service listening for audio input.
	 */
	private fun startListening() {
		try {
			if (recognizer != null) {
				if (speechService == null) {
					speechService = SpeechService(recognizer, 16000f)
				}
				speechService?.startListening(this)
				Timber.i("SpeechService listening started")
				Log.d("LucyVoice", "SpeechService active and listening")
				updateNotification("Listening for 'Lucy'...")
			}
		} catch (e: Exception) {
			Timber.e(e, "Failed to start SpeechService")
			Log.e("LucyVoice", "Error starting SpeechService", e)
		}
	}

	/**
	 * Helper to extract spoken text from Vosk JSON response.
	 */
	private fun parseVoskText(hypothesis: String?): String {
		if (hypothesis.isNullOrEmpty()) return ""
		return try {
			val jsonObject = JSONObject(hypothesis)
			if (jsonObject.has("text")) {
				jsonObject.getString("text")
			} else if (jsonObject.has("partial")) {
				jsonObject.getString("partial")
			} else {
				""
			}
		} catch (e: Exception) {
			""
		}
	}

	override fun onPartialResult(hypothesis: String?) {
		val text = parseVoskText(hypothesis).trim()
		if (text.isNotEmpty()) {
			Timber.d("Partial result: $text")
		}
	}

	override fun onResult(hypothesis: String?) {
		val text = parseVoskText(hypothesis).trim()
		if (text.isNotEmpty()) {
			Log.d("LucyVoice", "Heard: $text")
			Timber.i("Recognition result: $text")
			mainHandler.post {
				processVoiceCommand(text)
			}
		}
	}

	override fun onFinalResult(hypothesis: String?) {
		val text = parseVoskText(hypothesis).trim()
		if (text.isNotEmpty()) {
			Log.d("LucyVoice", "Heard: $text")
			Timber.i("Final result: $text")
			mainHandler.post {
				processVoiceCommand(text)
			}
		}
	}

	override fun onError(exception: Exception?) {
		Timber.e(exception, "Recognition error")
		Log.e("LucyVoice", "Recognition error", exception)
		mainHandler.post {
			updateNotification("Listening...")
		}
	}

	override fun onTimeout() {
		Timber.i("Recognition timeout")
		mainHandler.post {
			updateNotification("Listening for 'Lucy'...")
		}
	}

	/**
	 * Process recognized text and pass to VoiceCommandExecutor.
	 */
	private fun processVoiceCommand(voiceText: String) {
		Timber.i("Processing command: $voiceText")

		val commandExecuted = executor.executeCommand(voiceText) { confirmationPrompt ->
			Timber.i("Confirmation required: $confirmationPrompt")
			updateNotification("Awaiting confirmation: $confirmationPrompt")
		}

		if (commandExecuted) {
			updateNotification("Executed: $voiceText")
		} else {
			updateNotification("Command not recognized")
		}

		mainHandler.postDelayed({
			updateNotification("Listening for 'Lucy'...")
		}, 2500)
	}

	/**
	 * Update the foreground notification.
	 */
	private fun updateNotification(status: String) {
		notificationManager.notify(NOTIFICATION_ID, getNotification(status))
	}

	override fun onDestroy() {
		super.onDestroy()
		Timber.d("VoiceCommandService destroyed")
		speechService?.stop()
		speechService?.shutdown()
		recognizer?.close()
		model?.close()
		speakerVerifier.release()
		serviceScope.cancel()
	}
}
