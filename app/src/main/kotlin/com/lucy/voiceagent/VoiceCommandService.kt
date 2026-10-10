package com.lucy.voiceagent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import timber.log.Timber
import java.io.File

/**
 * ForegroundService for continuous voice listening.
 * Directly reads microphone PCM audio via AudioRecord and passes wave forms to Vosk Recognizer.
 */
class VoiceCommandService : Service() {

	private val binder = LocalBinder()
	private var recognizer: Recognizer? = null
	private var model: Model? = null
	private var audioRecord: AudioRecord? = null

	@Volatile
	private var isListening = false

	private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
	private val mainHandler = Handler(Looper.getMainLooper())

	private lateinit var executor: VoiceCommandExecutor

	private val notificationManager: NotificationManager by lazy {
		getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
	}

	companion object {
		private const val CHANNEL_ID = "VoiceCommandService"
		private const val NOTIFICATION_ID = 1001
		private const val MODEL_DIR = "vosk_model"
		private const val MODEL_FILE = "model-en-us"
		private const val SAMPLE_RATE = 16000
	}

	inner class LocalBinder : Binder() {
		fun getService(): VoiceCommandService = this@VoiceCommandService
	}

	override fun onCreate() {
		super.onCreate()
		Timber.d("VoiceCommandService created")

		LibVosk.setLogLevel(LogLevel.INFO)
		executor = VoiceCommandExecutor()

		createNotificationChannel()
		initializeVosk()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		Timber.d("VoiceCommandService onStartCommand")
		if (intent?.action == "STOP_SERVICE") {
			stopListening()
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
	private fun getNotification(status: String = "Listening for 'Lucy'..."): Notification {
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
	 * Initialize Vosk model and recognizer.
	 */
	private fun initializeVosk() {
		serviceScope.launch(Dispatchers.IO) {
			try {
				val modelDir = File(cacheDir, MODEL_DIR)
				val modelPath = File(modelDir, MODEL_FILE)

				if (!modelPath.exists()) {
					Timber.i("Model not found locally. Extracting from assets...")
					extractModelFromAssets(modelPath)
				}

				model = Model(modelPath.absolutePath)
				recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

				Timber.i("Vosk model loaded successfully")
				Log.d("LucyVoice", "Vosk model loaded successfully")

				mainHandler.post {
					startForeground(NOTIFICATION_ID, getNotification("Listening for 'Lucy'..."))
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
	 * Start recording audio from microphone in a background thread and feeding Vosk recognizer.
	 */
	@SuppressLint("MissingPermission")
	private fun startListening() {
		if (isListening) return
		val rec = recognizer ?: return

		isListening = true
		Log.d("LucyVoice", "Audio recording thread started. Listening for wake word 'Lucy'...")

		serviceScope.launch(Dispatchers.IO) {
			val minBufferSize = AudioRecord.getMinBufferSize(
				SAMPLE_RATE,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT
			)
			val bufferSize = maxOf(minBufferSize, 8192)
			val buffer = ShortArray(4096)

			try {
				val record = AudioRecord(
					MediaRecorder.AudioSource.MIC,
					SAMPLE_RATE,
					AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT,
					bufferSize
				)
				audioRecord = record

				if (record.state != AudioRecord.STATE_INITIALIZED) {
					Log.e("LucyVoice", "AudioRecord initialization failed")
					isListening = false
					return@launch
				}

				record.startRecording()
				Log.d("LucyVoice", "AudioRecord started recording")

				while (isListening && isActive) {
					val readSamples = record.read(buffer, 0, buffer.size)
					if (readSamples > 0) {
						if (rec.acceptWaveForm(buffer, readSamples)) {
							val jsonResult = rec.result
							processVoskResult(jsonResult)
						}
					}
				}

				try {
					record.stop()
				} catch (e: Exception) {
					Timber.e(e, "Error stopping AudioRecord")
				}
				record.release()
				audioRecord = null

			} catch (e: Exception) {
				Log.e("LucyVoice", "Audio recording loop error", e)
				Timber.e(e, "Audio recording loop error")
			} finally {
				isListening = false
			}
		}
	}

	/**
	 * Stop microphone audio recording loop.
	 */
	private fun stopListening() {
		isListening = false
		try {
			audioRecord?.stop()
			audioRecord?.release()
			audioRecord = null
		} catch (e: Exception) {
			Timber.e(e, "Error releasing AudioRecord")
		}
	}

	/**
	 * Parse Vosk JSON result, check for wake word 'lucy', and pass remaining command to executor.
	 */
	private fun processVoskResult(jsonResult: String?) {
		if (jsonResult.isNullOrEmpty()) return

		try {
			val jsonObject = JSONObject(jsonResult)
			val rawText = jsonObject.optString("text", "").lowercase().trim()

			if (rawText.isEmpty()) return

			Log.d("LucyVoice", "Heard text: $rawText")

			if (rawText.contains("lucy")) {
				val index = rawText.indexOf("lucy")
				val command = rawText.substring(index + "lucy".length)
					.trim()
					.removePrefix(",")
					.removePrefix("-")
					.trim()

				Log.d("LucyVoice", "Final Command: $command")

				if (command.isNotEmpty()) {
					mainHandler.post {
						updateNotification("Command: $command")
						val executed = executor.executeCommand(this@VoiceCommandService, command)
						if (executed) {
							updateNotification("Launched: $command")
						} else {
							updateNotification("Not found: $command")
						}
					}
				}
			}
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error parsing Vosk result JSON", e)
		}
	}

	/**
	 * Update the foreground notification text.
	 */
	private fun updateNotification(status: String) {
		notificationManager.notify(NOTIFICATION_ID, getNotification(status))
	}

	override fun onDestroy() {
		super.onDestroy()
		Timber.d("VoiceCommandService destroyed")
		Log.d("LucyVoice", "VoiceCommandService destroyed")
		stopListening()
		recognizer?.close()
		model?.close()
		serviceScope.cancel()
	}
}
